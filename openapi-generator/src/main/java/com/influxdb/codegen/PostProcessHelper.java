package com.influxdb.codegen;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.intellij.lang.annotations.Language;
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
		// Set DateTimeSchema for DateTimeLiteral
		//
		{
			Schema dateTimeSchema = new DateTimeSchema()
					.type("string")
					.format("date-time");
			changePropertySchema("value", "DateTimeLiteral", dateTimeSchema);
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
			pathItem.readOperationsMap().forEach(new BiConsumer<PathItem.HttpMethod, Operation>()
			{
				@Override
				public void accept(final PathItem.HttpMethod method, final Operation operation)
				{
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
				}
			});
		}
	}

	void postProcessModels(Map<String, Object> allModels) {

		// Iterate all models
		for (Map.Entry<String, Object> entry : allModels.entrySet())
		{
			CodegenModel pluginModel = getModel((HashMap) entry.getValue());

			//
			// Set correct inheritance. The "interfaces" extends base object.
			//
			if (!pluginModel.hasVars && pluginModel.interfaceModels != null)
			{
				if (pluginModel.getName().matches("(.*)Check(.*)|(.*)Notification(.*)")) {
					continue;
				}

				for (CodegenModel interfaceModel : pluginModel.interfaceModels)
				{
					interfaceModel.setParent(pluginModel.classname);
				}

				pluginModel.interfaces.clear();
				pluginModel.interfaceModels.clear();
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
}
