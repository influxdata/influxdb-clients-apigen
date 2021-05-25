package com.influxdb.codegen;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
import org.openapitools.codegen.CodegenProperty;

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
		// Fix DBRP schema
		//
		{
			ComposedSchema dbrp = (ComposedSchema) openAPI.getComponents().getSchemas().get("DBRP");
			Schema firstOneOf = dbrp.getOneOf().get(0);
			Schema newDBRP = new ObjectSchema().properties(dbrp.getProperties()).required(firstOneOf.getRequired());
			openAPI.getComponents().getSchemas().put("DBRP", newDBRP);
		}

		//
		// Use generic scheme for Telegraf plugins instead of TelegrafInputCPU, TelegrafInputMem, ...
		//
		{
			Schema newPropertySchema = new ObjectSchema().additionalProperties(new ObjectSchema());
			changePropertySchema("config", "TelegrafPlugin", newPropertySchema);
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
		// Fix required parameters
		//
		{
			Schema schema = openAPI.getComponents().getSchemas().get("NotificationRuleBase");
			schema.getRequired().removeAll(Arrays.asList("tagRules"));
		}

		//
		// Drop empty body
		//
		{
			PathItem pathItem = openAPI.getPaths().get("/tasks/{taskID}/runs/{runID}/retry");
			pathItem.getPost().requestBody(null);
		}

		//
		// Trim description
		//
		openAPI.getComponents().getParameters().forEach((s, parameter) -> {
			String description = parameter.getDescription();
			if (description != null) {
				parameter.setDescription(description.trim());
			}
		});
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

			//
			// Trim description
			//
			model.getAllVars().forEach(var -> {
				String description = var.getDescription();
				if (description != null) {
					var.setDescription(description.trim());
				}
			});
		}

		fixInheritance("Check", Arrays.asList("Deadman", "Custom", "Threshold"), allModels);
		fixInheritance("Threshold", Arrays.asList("Greater", "Lesser", "Range"), allModels);
		fixInheritance("NotificationEndpoint", Arrays.asList("Slack", "PagerDuty", "HTTP", "Telegram"), allModels);
		fixInheritance("NotificationRule", Arrays.asList("Slack", "PagerDuty", "SMTP", "HTTP", "Telegram"), allModels);

		// Iterate all models
		for (Map.Entry<String, Object> entry : allModels.entrySet())
		{
			CodegenModel model = getModel((HashMap) entry.getValue());
			String modelName = model.getName();

			if (modelName.matches("(.*)Check(.*)|(.*)Threshold(.*)|(.*)NotificationEndpoint(.*)|(.*)NotificationRule(.*)") && !"CheckViewProperties".equals(modelName)) {
				continue;
			}

			//
			// Set parent vars extension => useful for Object initialization
			//
			if (model.getParent() != null) {
				CodegenModel parentModel = getModel((HashMap) allModels.get(model.getParent()));
				setParentVars(model, parentModel, parentModel.getVars());
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

		//
		// Trim description.
		//
		if (operation.getParameters() != null)
		{
			operation.getParameters().forEach(parameter -> {
				String description = parameter.getDescription();
				if (description != null)
				{
					parameter.description(description.trim());
				}
			});
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

		CodegenModel discriminatorModel = schema;
		CodegenDiscriminator discriminator = schema.getDiscriminator();
		// Try to find intermediate entity -> Check -> CheckDiscriminator -> CheckBase
		if (allModels.containsKey(name + "Discriminator"))
		{
			discriminatorModel = getModel((HashMap) allModels.get(name + "Discriminator"));
			discriminator = discriminatorModel.getDiscriminator();
		}
		String discriminatorPropertyName = discriminator.getPropertyName();

		discriminatorModel.setChildren(new ArrayList<>());
		discriminatorModel.hasChildren = false;
		discriminatorModel.setParentModel(base);
		discriminatorModel.setParent(base.getName());
		discriminatorModel.setParentSchema(base.getName());
		setParentVars(discriminatorModel, base.getVars());

		List<CodegenModel> modelsInDiscriminator = mappings.stream()
				.map(mapping -> getModel((HashMap) allModels.get(mapping + name)))
				.collect(Collectors.toList());

		for (CodegenModel modelInDiscriminator : modelsInDiscriminator)
		{
			CodegenModel discriminatorModelBase = modelInDiscriminator;
			// if there is BaseModel then extend this SlackNotificationRule > SlackNotificationRuleBase
			if (allModels.containsKey(modelInDiscriminator.name + "Base")) {
				discriminatorModelBase = getModel((HashMap) allModels.get(modelInDiscriminator.name + "Base"));
				modelInDiscriminator.setParentModel(discriminatorModelBase);
				modelInDiscriminator.setParent(discriminatorModelBase.getName());
				modelInDiscriminator.setParentSchema(discriminatorModelBase.getName());

				// add parent vars from base and also from discriminator
				ArrayList<CodegenProperty> objects = new ArrayList<>();
				objects.addAll(discriminatorModelBase.getVars());
				objects.get(objects.size() - 1).hasMore = true;
				objects.addAll(base.getVars());

				setParentVars(modelInDiscriminator, objects);
			}

			discriminatorModelBase.setParentModel(discriminatorModel);
			discriminatorModelBase.setParent(discriminatorModel.getName());
			discriminatorModelBase.setParentSchema(discriminatorModel.getName());
			setParentVars(discriminatorModelBase, base.getVars());

			// set correct name for discriminator
			String discriminatorKey = discriminator.getMappedModels()
					.stream()
					.filter(mapped -> modelInDiscriminator.name.equals(mapped.getModelName()))
					.findFirst()
					.map(CodegenDiscriminator.MappedModel::getMappingName)
					.get();

			// Set default value for type
			{
				String discriminatorKeyDefaultValue = "\"" + discriminatorKey + "\"";

				String msg = String.format("The model in discriminator: %s doesn't have a discriminator property: %s",
						modelInDiscriminator, discriminatorPropertyName);
				// set to own properties
				CodegenProperty discriminatorVar = modelInDiscriminator
						.getRequiredVars()
						.stream()
						.filter(it -> it.getName().equals(discriminatorPropertyName))
						.findFirst()
						.orElseThrow(() -> new IllegalStateException(msg));
				discriminatorVar.defaultValue = discriminatorKeyDefaultValue;

				// set to parent vars
				List<CodegenProperty> parentVars = (List<CodegenProperty>) modelInDiscriminator
						.getVendorExtensions()
						.get("x-parent-vars");
				parentVars.stream()
					.filter(it -> it.getName().equals(discriminatorPropertyName))
					.findFirst().map(new Function<CodegenProperty, Void>()
				{
					@Override
					public Void apply(final CodegenProperty codegenProperty)
					{
						codegenProperty.defaultValue = discriminatorKeyDefaultValue;
						return null;
					}
				});

			}

			modelInDiscriminator.vendorExtensions.put("x-discriminator-value", discriminatorKey);
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
			rootModel.setDiscriminator(discriminator);
			rootModel.setChildren(modelsInDiscriminator);
			rootModel.hasChildren = true;

			// If there is no intermediate entity, than leave current parent schema
			if (allModels.containsKey(name + "Discriminator")) {
				rootModel.setParentSchema(null);
				rootModel.setParent(null);
			}

			boolean presentDiscriminatorVar = rootModel
					.getRequiredVars()
					.stream()
					.anyMatch(codegenProperty -> codegenProperty.getName().equals(discriminatorPropertyName));

			// there isn't discriminator property => add from discriminator model
			if (!presentDiscriminatorVar)
			{
				String msg = String.format("The discriminator model: %s doesn't have a discriminator property: %s",
						discriminatorModel, discriminatorPropertyName);

				CodegenProperty discriminatorVar = discriminatorModel
						.getRequiredVars()
						.stream()
						.filter(it -> it.getName().equals(discriminatorPropertyName))
						.findFirst()
						.orElseThrow(() -> new IllegalStateException(msg));

				rootModel.getVars().add(discriminatorVar);
				rootModel.getRequiredVars().add(discriminatorVar);
				rootModel.getAllVars().add(discriminatorVar);
			}
		}

		// remove discriminator property from inherited Discriminator
		if (discriminatorModel != base) {
			discriminatorModel
					.getRequiredVars()
					.removeIf(codegenProperty -> codegenProperty.getName().equals(discriminatorPropertyName));
			discriminatorModel
					.getAllVars()
					.removeIf(codegenProperty -> codegenProperty.getName().equals(discriminatorPropertyName));
			discriminatorModel.setDiscriminator(null);
		}
	}

	private void setParentVars(final CodegenModel model, final List<CodegenProperty> vars)
	{
		setParentVars(model, model.getParentModel(), vars);
	}

	private void setParentVars(final CodegenModel model, final CodegenModel parentModel, final List<CodegenProperty> vars)
	{
		List<CodegenProperty> cloned = new ArrayList<>();
		vars.forEach(codegenProperty -> cloned.add(codegenProperty.clone()));

		model.vendorExtensions.put("x-has-parent-vars", !cloned.isEmpty());
		model.vendorExtensions.put("x-parent-vars", cloned);
		model.vendorExtensions.put("x-parent-classFilename", parentModel.getClassFilename());
	}
}
