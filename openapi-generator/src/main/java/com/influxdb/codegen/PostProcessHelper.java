package com.influxdb.codegen;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.intellij.lang.annotations.Language;
import org.openapitools.codegen.CodegenDiscriminator;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.utils.StringUtils;

/**
 * @author Jakub Bednar (18/05/2021 13:20)
 */
class PostProcessHelper
{
	private final OpenAPI openAPI;

	public PostProcessHelper(final OpenAPI openAPI)
	{
		this.openAPI = openAPI;
	}

	void postProcessOpenAPI()
	{
		//
		// Drop supports for InfluxQL
		//
		{
			RequestBody requestBody = openAPI.getPaths().get("/query").getPost().getRequestBody();
			MediaType mediaType = requestBody.getContent().get("application/json");
			// Set correct schema to `Query` object
			Schema schema = ((ComposedSchema) mediaType.getSchema()).getOneOf().get(0);
			mediaType.schema(schema);
			dropSchemas("InfluxQLQuery");
		}

		//
		// Set correct name for NotificationEndpointDiscrimator
		//
		{
			Schema schema = openAPI.getComponents().getSchemas().remove("NotificationEndpointDiscrimator");
			openAPI.getComponents().getSchemas().put("NotificationEndpointDiscriminator", schema);
		}

		//
		// Use generic scheme for Telegraf plugins instead of TelegrafInputCPU, TelegrafInputMem, ...
		//
		{
			Schema newPropertySchema = new ObjectSchema().additionalProperties(new ObjectSchema());
			changePropertySchema("config", "TelegrafPlugin", newPropertySchema);

			// remove plugins
			dropSchemas("TelegrafPluginInput(.+)|TelegrafPluginOutput(.+)|TelegrafRequestPlugin");
		}

		//
		// Drop supports for Geo
		//
		{
			dropSchemas("Geo(.*)View(.*)");
		}

		//
		// Drop supports for Templates, Stack
		//
		{
			dropSchemas("Stack(.*)|Template(.*)");
			dropPaths("/stacks(.*)|/templates(.*)");
		}

		//
		// Set correct name for Inline Request Body
		//
		for (PathItem pathItem: openAPI.getPaths().values()) {
			pathItem.readOperationsMap().forEach((method, operation) -> {
				// find only operation with body
				if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
					return;
				}

				for (MediaType mediaType: operation.getRequestBody().getContent().values()) {
					Schema schema = mediaType.getSchema();
					// find only body with InlineObject and without Title
					if (schema instanceof ObjectSchema && schema.get$ref() == null && schema.getTitle() == null)
					{
						// set name from operationId: "PatchDashboardsID" => "PatchPatchDashboardsIDRequest"
						String title = method.name() + " " + operation.getSummary() + " " + "Request";
						schema.title(StringUtils.camelize(StringUtils.underscore(title)));
					}
				}
			});
		}
	}

	void postProcessModels(Map<String, Object> allModels) {

		for (Map.Entry<String, Object> entry : allModels.entrySet())
		{
			CodegenModel model = getModel((HashMap) entry.getValue());

			//
			// Set correct inheritance. The "interfaces" extends base object.
			//
			if (!model.hasVars && model.interfaceModels != null)
			{
				if (model.getName().matches("(.*)Check(.*)|(.*)Notification(.*)")) {
					continue;
				}

				for (CodegenModel interfaceModel : model.interfaceModels)
				{
					interfaceModel.setParent(model.classname);
				}

				model.interfaces.clear();
				model.interfaceModels.clear();
			}
		}

		fixInheritance("Check", Arrays.asList("Deadman", "Custom", "Threshold"), allModels);
		fixInheritance("Threshold", Arrays.asList("Greater", "Lesser", "Range"), allModels);
		fixInheritance("NotificationEndpoint", Arrays.asList("Slack", "PagerDuty", "HTTP", "Telegram"), allModels);
		fixInheritance("NotificationRule", Arrays.asList("Slack", "PagerDuty", "SMTP", "HTTP", "Telegram"), allModels);

		// Iterate all models
		for (Map.Entry<String, Object> entry : allModels.entrySet())
		{
			CodegenModel model = getModel((HashMap) entry.getValue());

			//
			// Set parent vars extension => useful for Object initialization
			//
			if (model.getParent() != null) {
				CodegenModel parentModel = getModel((HashMap) allModels.get(model.getParent()));
				model.vendorExtensions.put("x-parent-classFilename", parentModel.getClassFilename());
				model.vendorExtensions.put("x-has-parent-vars", !parentModel.getVars().isEmpty());
				model.vendorExtensions.put("x-parent-vars", parentModel.getVars());
			}
		}

	}

	void postProcessOperation(String path, Operation operation, CodegenOperation op)
	{
		//
		// Set correct path for /health, /ready, /setup ...
		//
		String url;
		if (operation.getServers() != null) {
			url = operation.getServers().get(0).getUrl();
		} else if (openAPI.getPaths().get(path).getServers() != null) {
			url = openAPI.getPaths().get(path).getServers().get(0).getUrl();
		} else {
			url = openAPI.getServers().get(0).getUrl();
		}

		if (url != null) {
			url = url.replaceAll("https://raw.githubusercontent.com", "");
		}

		if (!"/".equals(url) && url != null) {
			op.path = url + op.path;
		}
	}

	@Nonnull
	CodegenModel getModel(@Nonnull final HashMap modelConfig) {

		HashMap models = (HashMap) ((ArrayList) modelConfig.get("models")).get(0);

		return (CodegenModel) models.get("model");
	}

	private void changePropertySchema(final String property, final String schema, final Schema propertySchema)
	{
		ObjectSchema objectSchema = (ObjectSchema) openAPI.getComponents().getSchemas().get(schema);

		Map<String, Schema> properties = objectSchema.getProperties();
		properties.put(property, propertySchema.description(properties.get(property).getDescription()));
	}

	private void dropSchemas(@Language("RegExp") final String regexp)
	{
		openAPI.getComponents()
				.getSchemas()
				.entrySet()
				.removeIf(entry -> entry.getKey().matches(regexp));
	}

	private void dropPaths(@Language("RegExp") final String regex)
	{
		openAPI.getPaths()
				.entrySet()
				.removeIf(entry -> entry.getKey().matches(regex));
	}

	private void fixInheritance(final String name, final List<String> mappings, final Map<String, Object> allModels)
	{
		CodegenModel schema = getModel((HashMap) allModels.get(name));
		CodegenModel base = getModel((HashMap) allModels.get(name + "Base"));
		CodegenModel discriminator = schema;
		// Try to find intermediate entity -> Check -> CheckDiscriminator -> CheckBase
		if (allModels.containsKey(name + "Discriminator"))
		{
			discriminator = getModel((HashMap) allModels.get(name + "Discriminator"));
		}

		discriminator.setChildren(new ArrayList<>());
		discriminator.hasChildren = false;
		discriminator.setParentModel(base);
		discriminator.setParent(base.getName());
		discriminator.setParentSchema(base.getName());
		discriminator.setParentVars(base.getVars());

		List<CodegenModel> discriminatorModels = mappings.stream()
				.map(mapping -> getModel((HashMap) allModels.get(mapping + name)))
				.collect(Collectors.toList());

		for (CodegenModel discriminatorModel : discriminatorModels)
		{
			CodegenModel discriminatorModelBase = discriminatorModel;
			// if there is BaseModel then extend this SlackNotificationRule > SlackNotificationRuleBase
			if (allModels.containsKey(discriminatorModel.name + "Base")) {
				discriminatorModelBase = getModel((HashMap) allModels.get(discriminatorModel.name + "Base"));
				discriminatorModel.setParentModel(discriminatorModelBase);
				discriminatorModel.setParent(discriminatorModelBase.getName());
				discriminatorModel.setParentSchema(discriminatorModelBase.getName());
				discriminatorModel.vendorExtensions.put("x-has-parent-vars", !base.getVars().isEmpty());
				discriminatorModel.vendorExtensions.put("x-parent-vars", base.getVars());
			}

			discriminatorModelBase.setParentModel(discriminator);
			discriminatorModelBase.setParent(discriminator.getName());
			discriminatorModelBase.setParentSchema(discriminator.getName());
			discriminatorModelBase.vendorExtensions.put("x-has-parent-vars", !base.getVars().isEmpty());
			discriminatorModelBase.vendorExtensions.put("x-parent-vars", base.getVars());

			// set correct name for discriminator
			String discriminatorKey = discriminator.getDiscriminator().getMappedModels()
					.stream()
					.filter(mapped -> discriminatorModel.name.equals(mapped.getModelName()))
					.findFirst()
					.map(CodegenDiscriminator.MappedModel::getMappingName)
					.get();

			discriminatorModel.vendorExtensions.put("x-discriminator-value", discriminatorKey);
		}

		// If there is also Post schema then use same discriminator: Check, PostCheck
		List<CodegenModel> rootModels = new ArrayList<>();
		rootModels.add(schema);
		if (allModels.containsKey("Post" + name))
		{
			rootModels.add(getModel((HashMap) allModels.get("Post" + name)));
		}

		for (CodegenModel rootModel : rootModels)
		{
			rootModel.setDiscriminator(discriminator.getDiscriminator());
			rootModel.setChildren(discriminatorModels);
			rootModel.hasChildren = true;

			// If there is no intermediate entity, than leave current parent schema
			if (allModels.containsKey(name + "Discriminator")) {
				rootModel.setParentSchema(null);
				rootModel.setParent(null);
			}
		}
	}
}
