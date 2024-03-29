package com.influxdb.codegen;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.influxdb.AppendCloudDefinitions;

import com.google.common.collect.Lists;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.CookieParameter;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;
import org.openapitools.codegen.CodegenConfig;
import org.openapitools.codegen.CodegenDiscriminator;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.InlineModelResolver;
import org.openapitools.codegen.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Jakub Bednar (18/05/2021 13:20)
 */
class PostProcessHelper
{
	private static final Logger LOG = LoggerFactory.getLogger(PostProcessHelper.class);

	private final OpenAPI openAPI;
	private final InfluxGenerator generator;
	/**
	 * For "PostDashboards" we prefer to use "DashboardWithViewProperties".
	 *
	 * <ul>
	 *     <li>key - operation id</li>
	 *     <li>value - schema name</li>
	 * </ul>
	 */
	private final Map<String, String> preferredSchemaForMultipleResponseType = new HashMap<>();

	public PostProcessHelper(InfluxGenerator generator)
	{
		this.generator = generator;
		this.openAPI = generator.getOpenAPI();
	}

	/**
	 * @param operationId "PostDashboards"
	 * @param schemaName  "DashboardWithViewProperties"
	 * @return this
	 */
	@Nonnull
	PostProcessHelper addPreferredSchemaForMultipleResponseType(String operationId, String schemaName)
	{
		preferredSchemaForMultipleResponseType.put(operationId, schemaName);
		return this;
	}

	void postProcessOpenAPI()
	{
		//
		// Drop available security schemas if the client uses own definition of Auth header
		//
		if (generator.usesOwnAuthorizationSchema())
		{
			List<SecurityRequirement> security = openAPI.getSecurity();
			if (security != null)
			{
				security.clear();
			}
		}

		for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet())
		{
			for (Operation operation : entry.getValue().readOperations())
			{
				for (Map.Entry<String, ApiResponse> e : operation.getResponses().entrySet())
				{
					Content content = e.getValue().getContent();
					if (content == null)
					{
						continue;
					}
					for (MediaType mediaType : content.values())
					{
						Schema schema = mediaType.getSchema();
						//
						// Use first response type for multiple response type by oneOf (Dashboard, DashboardWithViewProperties)
						//
						if (schema instanceof ComposedSchema)
						{
							List<Schema> composedSchema = ((ComposedSchema) (schema)).getOneOf();
							if (composedSchema != null && composedSchema.size() > 1)
							{
								String preferredSchemaName = preferredSchemaForMultipleResponseType
										.get(operation.getOperationId());

								if (preferredSchemaName != null)
								{
									composedSchema.stream()
											.filter(it -> it.get$ref().endsWith(preferredSchemaName))
											.findFirst()
											.ifPresent(mediaType::setSchema);
								}
								else
								{
									mediaType.setSchema(composedSchema.get(0));
								}
							}
						}
						// set name of response schema for inline schemas
						if (schema instanceof ObjectSchema && schema.getTitle() == null)
						{
							schema.title(operation.getOperationId() + "Response");
						}
					}

					// cookie params not supported yet
					if (operation.getParameters() != null)
					{
						operation.getParameters().removeIf(p -> p instanceof CookieParameter);
					}
				}
			}
		}

		//
		// Use generic scheme for Telegraf plugins instead of TelegrafInputCPU, TelegrafInputMem, ...
		//
		{
			Schema newPropertySchema = new ObjectSchema().additionalProperties(new ObjectSchema());
			changePropertySchema("config", "TelegrafPlugin", newPropertySchema);

			Schema telegrafPluginRequest = openAPI.getComponents().getSchemas().get("TelegrafPluginRequest");
			if (telegrafPluginRequest != null)
			{
				Schema schema = ((ArraySchema) telegrafPluginRequest.getProperties().get("plugins"))
						.getItems();
				changePropertySchema("config", schema, newPropertySchema);
			}
		}

		//
		// Use generic schema for Flags and Config
		//
		{
			openAPI.getComponents().getSchemas().put("Flags", new ObjectSchema().additionalProperties(new Schema()));
			openAPI.getComponents().getSchemas().get("Config").getProperties().put("config", new ObjectSchema().additionalProperties(new Schema()));
		}

		//
		// Use String schema for /metrics response
		//
		{
			PathItem pathItem = openAPI.getPaths().get("/metrics");
			if (pathItem != null)
			{
				pathItem.readOperations()
						.forEach(operation -> {
							operation
									.getResponses()
									.forEach((responseKey, response) -> {
										response
												.getContent()
												.forEach((mediaTypeKey, mediaType) -> {
													mediaType.setSchema(new StringSchema());
												});
									});
						});
			}
		}

		//
		// Change PermissionResource type to String schema - fixup to avoid exception for non-existing item
		//
		if (generator.permissionResourceTypeAsString())
		{
			StringSchema oldSchema = (StringSchema) openAPI.getComponents().getSchemas().get("Resource").getProperties().get("type");
			StringSchema newSchema = new StringSchema();
			List<Map<String, String>> staticValues = oldSchema.getEnum().stream().map(item -> {
				Map<String, String> map = new HashMap<>();
				map.put("title", generator.toEnumVarName("type_" + org.openapitools.codegen.utils.StringUtils.camelize(item), oldSchema.getType()));
				map.put("value", item);

				return map;
			}).collect(Collectors.toList());
			newSchema.addExtension("x-static-values", staticValues);
			newSchema.addExtension("x-has-static-values", true);
			changePropertySchema("type", "Resource", newSchema);
		}

		//
		// Drop supports for Geo
		//
		{
			dropSchemas("Geo(.*)View(.*)");
			dropSchemas("LatLon(.*)");
			((ComposedSchema) openAPI.getComponents().getSchemas().get("ViewProperties"))
					.getOneOf()
					.removeIf(schema -> schema.get$ref().endsWith("GeoViewProperties"));
		}

		//
		// Templates, Stack API
		//
		{
			// Fix naming for ViewLink and ViewsLink schema
			{
				Schema viewSchema = openAPI.getComponents().getSchemas().get("View");
				Schema links = (Schema) viewSchema.getProperties().get("links");
				((Schema) links.getProperties().get("self")).setTitle("ViewLinks");

				Schema viewsSchema = openAPI.getComponents().getSchemas().get("Views");
				Schema viewsSchemaLinks = (Schema) viewsSchema.getProperties().get("links");
				((Schema) viewsSchemaLinks.getProperties().get("self")).setTitle("ViewLinks");
			}

			// Fix naming for 'new' and 'old' property of TemplateSummary.diff
			{
				Schema schema = openAPI.getComponents().getSchemas().get("TemplateSummary");
				ObjectSchema diff = (ObjectSchema) schema.getProperties().get("diff");
				for (Map.Entry<String, Schema> entry : diff.getProperties().entrySet())
				{
					String path = entry.getKey();
					ArraySchema pathSchema = (ArraySchema) entry.getValue();
					Schema newSchema = (Schema) pathSchema.getItems().getProperties().get("new");
					if (newSchema != null && newSchema.get$ref() == null)
					{
						newSchema.setTitle("TemplateSummary_Diff_" + path + "_new_old");
					}
				}
			}

			// Fix schema name for UpdateStack.additionalResources
			{
				Schema stackPatch = openAPI.getPaths()
						.get("/stacks/{stack_id}")
						.getPatch()
						.getRequestBody()
						.getContent()
						.get("application/json")
						.getSchema();
				Schema additionalResources = ((ArraySchema) stackPatch.getProperties().get("additionalResources"))
						.getItems();
				additionalResources.setTitle("PatchStackRequest_additionalResources");
			}

			//
			// Drop supports for TemplateExportByName
			//
			{
				RequestBody requestBody = openAPI.getPaths().get("/templates/export").getPost().getRequestBody();
				MediaType mediaType = requestBody.getContent().get("application/json");
				// Set correct schema to `TemplateExportByID` object
				Schema schema = ((ComposedSchema) mediaType.getSchema()).getOneOf().get(0);
				mediaType.schema(schema);
				dropSchemas("TemplateExportByName");
			}

			//
			// Drop if not supported
			//
			if (!generator.supportsStacksTemplates())
			{
				dropPaths("/stacks(.*)|/templates(.*)");
			}
		}

		//
		// Change type of generic Object to String
		//
		// see: https://github.com/influxdata/openapi/pull/90
		//
		{
			PathItem pathItem = openAPI.getPaths().get("/tasks/{taskID}/runs/{runID}/retry");
			pathItem.getPost()
					.getRequestBody()
					.getContent()
					.values().forEach(mediaType -> mediaType.setSchema(new StringSchema()));
		}

		//
		// Drop entity with multiple inheritance
		//
		if (generator.compileTimeInheritance())
		{
			Arrays.asList("/checks", "/notificationEndpoints", "/notificationRules").forEach(s -> {
				Operation post = openAPI.getPaths().get(s).getPost();

				Schema mediaType = post.getRequestBody().getContent().get("application/json").getSchema();
				// '#/components/schemas/PostCheck'
				String $ref = mediaType.get$ref();
				// '#/components/schemas/Post'
				mediaType.set$ref($ref.replace("Post", ""));
				// 'PostCheck'
				dropSchemas(StringUtils.substringAfterLast($ref, "/"));
			});
		}

		{
			//
			// Authorization header for operation with Basic Security
			//
			openAPI.getPaths().forEach((path, pathItem) -> {
				pathItem
						.readOperations().stream().filter(operation -> operation != null && operation.getSecurity() != null && !operation.getSecurity().isEmpty()).forEach(operation -> {
							boolean containsBasicAuth = operation
									.getSecurity()
									.stream()
									.anyMatch(securityRequirement -> securityRequirement.containsKey("BasicAuth") ||
											securityRequirement.containsKey("BasicAuthentication"));
							if (containsBasicAuth)
							{
								// the BasicAuth is not required for this operation
								if (operation.getOperationId().equals("PostUsersIDPassword"))
								{
									operation.security(null);
								}
								else
								{
									Parameter authorization = new HeaderParameter()
											.name("Authorization")
											.schema(new StringSchema())
											.description("An auth credential for the Basic scheme");
									operation.addParametersItem(authorization);
								}
							}
						});

				//
				// Keep Authorization API name to 'Authorizations'
				//
				for (Operation operation : pathItem.readOperations()) {
					operation
							.getTags()
							.replaceAll(tag -> tag.replace("Authorizations (API tokens)", "Authorizations"));
				}
			});
		}

		//
		// Correctly generate inline Objects = AuthorizationLinks
		//
		if (generator.compileTimeInheritance())
		{
			InlineModelResolver inlineModelResolver = new InlineModelResolver();
			inlineModelResolver.flatten(openAPI);

			String[] schemaNames = openAPI.getComponents().getSchemas().keySet().toArray(new String[0]);
			for (String schemaName : schemaNames)
			{

				Schema schema = openAPI.getComponents().getSchemas().get(schemaName);
				if (schema instanceof ComposedSchema)
				{
					List<Schema> allOf = ((ComposedSchema) schema).getAllOf();
					if (allOf != null)
					{
						allOf.forEach(child -> {

							if (child instanceof ObjectSchema)
							{
								inlineModelResolver.flattenProperties(child.getProperties(), schemaName);
							}
						});
					}
				}
			}
		}

		//
		// Specify possible types for TelegrafPlugin
		//
		{
			Schema telegrafPlugin = openAPI.getComponents().getSchemas().get("TelegrafPlugin");
			StringSchema type = (StringSchema) telegrafPlugin.getProperties().get("type");
			type._enum(Arrays.asList("input", "output"));
		}

//		openAPI.getTags().get(0).setName("Authorizations");

		//
		// Trim description
		//
		openAPI.getComponents().getParameters().forEach((s, parameter) -> {
			String description = parameter.getDescription();
			if (description != null)
			{
				parameter.setDescription(description.trim());
			}
		});
		for (PathItem path : openAPI.getPaths().values())
		{
			for (Operation operation : path.readOperations())
			{
				List<Parameter> parameters = operation.getParameters();
				if (parameters != null)
				{
					for (Parameter parameter : parameters)
					{
						String description = parameter.getDescription();
						if (description != null)
						{
							parameter.setDescription(description.trim());
						}
					}
				}
			}
		}
	}

	public void postProcessModel(final CodegenModel model, final Schema modelSchema, final Map<String, Schema> allDefinitions)
	{
		//
		// Set default values for Enum Variables
		//
		model.getAllVars().stream()
				.filter(property -> property.isEnum)
				.forEach(codegenProperty -> {
					if (codegenProperty.required && codegenProperty.defaultValue == null)
					{
						List values = (List) codegenProperty.allowableValues.get("values");
						// enum has only one value => default value
						if (values.size() == 1)
						{
							codegenProperty.defaultValue = (String) values.stream().findFirst().orElse(null);
						}
					}

					generator.updateCodegenPropertyEnum(codegenProperty);
				});

		//
		// Create adapters properties discriminator
		//
		{
			Map properties = modelSchema.getProperties();
			if (properties != null)
			{
				properties
						.forEach((BiConsumer<String, Schema>) (property, propertySchema) -> {

							Schema schema = propertySchema;

							//
							// Reference to List of Object
							//
							if (schema instanceof ArraySchema)
							{
								String ref = ((ArraySchema) schema).getItems().get$ref();
								if (ref != null)
								{
									String refSchemaName = ModelUtils.getSimpleRef(ref);
									Schema refSchema = allDefinitions.get(refSchemaName);

									if (refSchema instanceof ComposedSchema)
									{
										if (((ComposedSchema) refSchema).getOneOf() != null)
										{
											schema = refSchema;
										}
									}
								}
							}

							//
							// Reference to Object
							//
							else if (schema.get$ref() != null)
							{
								String refSchemaName = ModelUtils.getSimpleRef(schema.get$ref());
								Schema refSchema = allDefinitions.get(refSchemaName);

								if (refSchema instanceof ComposedSchema)
								{
									if (((ComposedSchema) refSchema).getOneOf() != null)
									{
										schema = refSchema;
									}
								}
							}

							final Discriminator apiDiscriminator = schema.getDiscriminator();

							CodegenProperty codegenProperty = getCodegenProperty(model, property);
							if (codegenProperty != null)
							{
								String adapterName = model.getName() + codegenProperty.nameInCamelCase + "Adapter";
								PostProcessHelper.TypeAdapter typeAdapter = new PostProcessHelper.TypeAdapter();
								typeAdapter.classname = adapterName;

								Map<String, PostProcessHelper.TypeAdapter> adapters = (HashMap<String, PostProcessHelper.TypeAdapter>) model.vendorExtensions
										.getOrDefault("x-type-adapters", new HashMap<String, PostProcessHelper.TypeAdapter>());

								if (apiDiscriminator != null)
								{

									apiDiscriminator.getMapping().forEach((mappingKey, refSchemaName) ->
									{
										typeAdapter.isArray = propertySchema instanceof ArraySchema;
										typeAdapter.discriminator = Stream.of(apiDiscriminator.getPropertyName())
												.map(v -> "\"" + v + "\"")
												.collect(Collectors.joining(", "));
										PostProcessHelper.TypeAdapterItem typeAdapterItem = new PostProcessHelper.TypeAdapterItem();
										typeAdapterItem.discriminatorValue = Stream.of(mappingKey).map(v -> "\"" + v + "\"").collect(Collectors.joining(", "));
										typeAdapterItem.classname = ModelUtils.getSimpleRef(refSchemaName);
										typeAdapter.items.add(typeAdapterItem);
									});
								}

								if (apiDiscriminator == null && schema instanceof ComposedSchema)
								{

									for (Schema oneOf : getOneOf(schema, allDefinitions))
									{

										String refSchemaName;
										Schema refSchema;

										if (oneOf.get$ref() == null)
										{
											refSchema = oneOf;
											refSchemaName = oneOf.getName();
										}
										else
										{
											refSchemaName = ModelUtils.getSimpleRef(oneOf.get$ref());
											refSchema = allDefinitions.get(refSchemaName);
											if (refSchema instanceof ComposedSchema)
											{
												List<Schema> schemaList = ((ComposedSchema) refSchema).getAllOf().stream()
														.map(it -> getObjectSchemas(it, allDefinitions))
														.flatMap(Collection::stream)
														.filter(it -> it instanceof ObjectSchema).collect(Collectors.toList());
												refSchema = schemaList
														.stream()
														.filter(it -> {
															for (Schema ps : (Collection<Schema>) it.getProperties().values())
															{
																if (ps.getEnum() != null && ps.getEnum().size() == 1)
																{
																	return true;
																}
															}
															return false;
														})
														.findFirst()
														.orElse(schemaList.get(0));
											}
										}

										String[] keys = getDiscriminatorKeys(schema, refSchema);

										String[] discriminator = new String[]{};
										String[] discriminatorValue = new String[]{};

										for (String key : keys)
										{
											Schema keyScheme = (Schema) refSchema.getProperties().get(key);
											if (keyScheme.get$ref() != null)
											{
												keyScheme = allDefinitions.get(ModelUtils.getSimpleRef(keyScheme.get$ref()));
											}

											if (!(keyScheme instanceof StringSchema))
											{
												continue;
											}
											else
											{

												if (((StringSchema) keyScheme).getEnum() != null)
												{
													discriminatorValue = ArrayUtils.add(discriminatorValue, ((StringSchema) keyScheme).getEnum().get(0));
												}
												else
												{
													discriminatorValue = ArrayUtils.add(discriminatorValue, refSchemaName);
												}
											}

											discriminator = ArrayUtils.add(discriminator, key);
										}

										typeAdapter.isArray = propertySchema instanceof ArraySchema;
										typeAdapter.discriminator = Stream.of(discriminator).map(v -> "\"" + v + "\"").collect(Collectors.joining(", "));
										PostProcessHelper.TypeAdapterItem typeAdapterItem = new PostProcessHelper.TypeAdapterItem();
										typeAdapterItem.discriminatorValue = Stream.of(discriminatorValue).map(v -> "\"" + v + "\"").collect(Collectors.joining(", "));
										typeAdapterItem.classname = refSchemaName;
										typeAdapter.items.add(typeAdapterItem);
									}
								}

								if (!typeAdapter.items.isEmpty())
								{

									codegenProperty.vendorExtensions.put("x-has-type-adapter", Boolean.TRUE);
									codegenProperty.vendorExtensions.put("x-type-adapter", adapterName);

									adapters.put(adapterName, typeAdapter);

									model.vendorExtensions.put("x-type-adapters", adapters);
									model.imports.addAll(generator.getTypeAdapterImports());
								}
							}
						});
			}
		}
	}

	void postProcessModelProperty(final CodegenModel model, final CodegenProperty property)
	{

		//
		// If its a constant then set default value
		//
		if (property.isEnum && property.get_enum() != null && property.get_enum().size() == 1)
		{
			property.isReadOnly = true;
			property.defaultValue = generator.toEnumConstructorDefaultValue(property.get_enum().get(0), property.enumName);
		}
	}

	void postProcessModels(Map<String, Object> allModels)
	{

		for (Map.Entry<String, Object> entry : allModels.entrySet())
		{
			CodegenModel model = getModel((HashMap) entry.getValue());

			//
			// Set correct inheritance. The "interfaces" extends base object.
			//
			if (!model.hasVars && model.interfaceModels != null)
			{
				if (model.getName().matches("(.*)Check(.*)|(.*)Threshold(.*)|(.*)Notification(.*)"))
				{
					continue;
				}

				for (CodegenModel interfaceModel : model.interfaceModels)
				{
					if (interfaceModel.getParent() == null)
					{
						interfaceModel.setParent(model.classname);
						interfaceModel.setParentSchema(model.getName());
					}
				}

				model.interfaces.clear();
				model.interfaceModels.clear();
			}

			//
			// Trim description
			//
			model.getAllVars().forEach(var -> {
				String description = var.getDescription();
				if (description != null)
				{
					var.setDescription(description.trim());
				}
			});
		}

		//
		// Fix structure of AST
		//
		{
			CodegenModel propertyKey = getModel((HashMap) allModels.get("PropertyKey"));
			CodegenModel identifier = getModel((HashMap) allModels.get("Identifier"));
			CodegenModel expression = getModel((HashMap) allModels.get("Expression"));
			CodegenModel stringLiteral = getModel((HashMap) allModels.get("StringLiteral"));

			identifier.setParentModel(propertyKey);
			identifier.setParent(propertyKey.getName());
			identifier.setParentSchema(propertyKey.getName());

			propertyKey.setParentModel(expression);
			propertyKey.setParent(expression.getName());
			propertyKey.setParentSchema(expression.getName());

			stringLiteral.setParentModel(propertyKey);
			stringLiteral.setParent(propertyKey.getName());
			stringLiteral.setParentSchema(propertyKey.getName());
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

			//
			// Fix enum vars
			//
			for (CodegenProperty allVar : model.getAllVars())
			{
				if (allVar.isEnum)
				{
					CodegenProperty codegenProperty = getCodegenProperty(model, allVar.baseName);
					if (codegenProperty != null && !codegenProperty.isEnum)
					{
						codegenProperty.isEnum = allVar.isEnum;
					}
				}
			}

			if (modelName.matches("(.*)Check(.*)|(.*)Threshold(.*)|(.*)NotificationEndpoint(.*)|(.*)NotificationRule(.*)") && !"CheckViewProperties".equals(modelName))
			{
				continue;
			}

			//
			// Set parent vars extension => useful for Object initialization
			//
			if (model.getParent() != null)
			{
				CodegenModel parentModel = getModel((HashMap) allModels.get(model.getParent()));
				setExtensionParentVars(model, parentModel, parentModel.getVars());

				//
				// remove readonly vars => we can't change readonly vars
				//
				removerReadonlyParentVars(model);
			}

			//
			// remove if its only parent for oneOf
			//
			Schema schema = openAPI.getComponents().getSchemas().get(modelName);
			if (schema instanceof ComposedSchema && ((ComposedSchema) schema).getOneOf() != null && !((ComposedSchema) schema).getOneOf().isEmpty())
			{
				model.getReadWriteVars().clear();
				model.hasOnlyReadOnly = true;
			}

			//
			// Fixed Parent Vars
			//
			if (model.getParentModel() != null && model.getParentModel().getReadWriteVars() != null)
			{
				List<CodegenProperty> parentReadWriteVars = model.getParentModel().getReadWriteVars();
				if (model.getParentVars().size() != parentReadWriteVars.size())
				{
					model.setParentVars(parentReadWriteVars);
				}
			}
		}

	}

	void postProcessOperation(String path, Operation operation, CodegenOperation op, final Map<String, Schema> definitions)
	{
		//
		// Set correct path for /health, /ready, /setup ...
		//
		String url;
		if (operation.getServers() != null)
		{
			url = operation.getServers().get(0).getUrl();
		}
		else if (openAPI.getPaths().get(path).getServers() != null)
		{
			url = openAPI.getPaths().get(path).getServers().get(0).getUrl();
		}
		else
		{
			url = openAPI.getServers().get(0).getUrl();
		}

		if (url != null)
		{
			url = url.replaceAll("https://raw.githubusercontent.com", "");
		}

		if (!"/".equals(url) && url != null)
		{
			op.path = url + op.path;
		}

		//
		// Set Optional data type for enum without default value.
		//
		String optionalDatatypeKeyword = generator.optionalDatatypeKeyword();
		if (optionalDatatypeKeyword != null)
		{
			op.allParams
					.stream()
					.filter(parameter -> parameter.defaultValue == null && !parameter.required && definitions.containsKey(parameter.dataType))
					.filter(parameter -> {
						List enums = definitions.get(parameter.dataType).getEnum();
						return enums != null && !enums.isEmpty();
					})
					.filter(parameter -> !parameter.dataType.endsWith(optionalDatatypeKeyword))
					.forEach(parameter -> parameter.dataType += optionalDatatypeKeyword);
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

		//
		// Trim notes.
		//
		String notes = op.notes;
		if (notes != null)
		{
			op.notes = notes.trim();
		}
	}

	public void postProcessOperationsWithModels(final Map<String, Object> objs) {
		HashMap operations = AppendCloudDefinitions.mapValue(new String[]{"operations"}, objs);
		String classname = AppendCloudDefinitions.mapValue(new String[]{"operations", "classname"}, objs);

		if (classname.startsWith("InvokableScripts")) {
			operations.put("cloudDocLink", "https://docs.influxdata.com/influxdb/cloud/api-guide/api-invokable-scripts/");
		} else if (classname.startsWith("BucketSchemas")) {
			operations.put("cloudDocLink", "https://docs.influxdata.com/influxdb/cloud/organizations/buckets/bucket-schema/");
		}
	}

	@Nonnull
	CodegenModel getModel(@Nonnull final HashMap modelConfig)
	{

		HashMap models = (HashMap) ((ArrayList) modelConfig.get("models")).get(0);

		return (CodegenModel) models.get("model");
	}

	/**
	 * Copy generated files to other location.
	 *
	 * @param sourceFile  source file path, path should be relative to client root path
	 * @param outputFiles output file paths, path should be relative to client root path
	 * @param config      with configured output
	 */
	void copyFiles(@Nonnull final String sourceFile,
				   @Nonnull final Collection<String> outputFiles,
				   @Nonnull final CodegenConfig config)
	{
		String outputFolder = config.outputFolder() + File.separator;

		File source = new File(outputFolder + sourceFile);
		for (String outputFile : outputFiles)
		{
			try
			{
				File output = new File(outputFolder + outputFile);

				LOG.info("copy file " + source + " -> " + output);
				FileUtils.copyFile(source, output);
			}
			catch (IOException e)
			{
				throw new RuntimeException(e);
			}
		}
	}

	private void changePropertySchema(final String property, final String schema, final Schema propertySchema)
	{
		ObjectSchema objectSchema = (ObjectSchema) openAPI.getComponents().getSchemas().get(schema);

		changePropertySchema(property, objectSchema, propertySchema);
	}

	private void changePropertySchema(final String property, final Schema oldSchema, final Schema newSchema)
	{
		Map<String, Schema> properties = oldSchema.getProperties();
		properties.put(property, newSchema.description(properties.get(property).getDescription()));
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
		discriminatorModel.setReadWriteVars(cloneVars(base.getReadWriteVars()));
		setToParentVars(discriminatorModel, base.getReadWriteVars());
		setExtensionParentVars(discriminatorModel, base.getVars());

		List<CodegenModel> modelsInDiscriminator = mappings.stream()
				.map(mapping -> getModel((HashMap) allModels.get(mapping + name)))
				.collect(Collectors.toList());

		for (CodegenModel modelInDiscriminator : modelsInDiscriminator)
		{
			CodegenModel discriminatorModelBase = modelInDiscriminator;
			// if there is BaseModel then extend this SlackNotificationRule > SlackNotificationRuleBase
			if (allModels.containsKey(modelInDiscriminator.name + "Base"))
			{
				discriminatorModelBase = getModel((HashMap) allModels.get(modelInDiscriminator.name + "Base"));
				modelInDiscriminator.setParentModel(discriminatorModelBase);
				modelInDiscriminator.setParent(discriminatorModelBase.getName());
				modelInDiscriminator.setParentSchema(discriminatorModelBase.getName());

				// add parent vars from base and also from discriminator
				ArrayList<CodegenProperty> objects = new ArrayList<>();
				objects.addAll(discriminatorModelBase.getVars());
				objects.addAll(base.getVars());

				setToParentVars(modelInDiscriminator, objects);
				setExtensionParentVars(modelInDiscriminator, objects);
			}

			if (generator.compileTimeInheritance())
			{
				discriminatorModelBase.setParentModel(schema);
				discriminatorModelBase.setParent(schema.getName());
				discriminatorModelBase.setParentSchema(schema.getName());

				List<CodegenProperty> parentVars = schema.getParentModel().getReadWriteVars();
				setToParentVars(discriminatorModelBase, parentVars);
				setExtensionParentVars(discriminatorModelBase, parentVars);

				setReadWriteWars(discriminatorModelBase, parentVars);

				discriminatorModelBase = schema;
				discriminatorModelBase.hasRequired = true;
				discriminatorModelBase.hasOnlyReadOnly = false;
			}

			if (discriminatorModelBase != discriminatorModel)
			{
				CodegenModel discriminatorModelBaseParen = discriminatorModel;
				if (!allModels.containsKey(name + "Discriminator"))
				{
					discriminatorModelBaseParen = base;
				}
				discriminatorModelBase.setParentModel(discriminatorModelBaseParen);
				discriminatorModelBase.setParent(discriminatorModelBaseParen.getName());
				discriminatorModelBase.setParentSchema(discriminatorModelBaseParen.getName());
				setToParentVars(discriminatorModelBase, discriminatorModelBaseParen.getParentVars());
				setExtensionParentVars(discriminatorModelBase, base.getVars());
				setReadWriteWars(discriminatorModelBase, discriminatorModelBaseParen.getParentVars());
			}

			// set correct name for discriminator
			String discriminatorKey = discriminator.getMappedModels()
					.stream()
					.filter(mapped -> modelInDiscriminator.name.equals(mapped.getModelName()))
					.findFirst()
					.map(CodegenDiscriminator.MappedModel::getMappingName)
					.get();

			// Set default value for type
			{
				String msg = String.format("The model in discriminator: %s doesn't have a discriminator property: %s",
						modelInDiscriminator, discriminatorPropertyName);
				// set to own properties
				CodegenProperty discriminatorVar = modelInDiscriminator
						.getRequiredVars()
						.stream()
						.filter(it -> it.getBaseName().equals(discriminatorPropertyName))
						.findFirst()
						.orElseThrow(() -> new IllegalStateException(msg));

				String discriminatorKeyDefaultValue = generator.toEnumConstructorDefaultValue(
						discriminatorKey,
						discriminatorVar.datatypeWithEnum);

				discriminatorVar.defaultValue = discriminatorKeyDefaultValue;

				// set to parent vars
				List<CodegenProperty> xParentVars = (List<CodegenProperty>) modelInDiscriminator
						.getVendorExtensions()
						.get("x-parent-vars");
				xParentVars.stream()
						.filter(it -> it.getBaseName().equals(discriminatorPropertyName))
						.findFirst()
						.map(new Function<CodegenProperty, Void>()
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

			if (!generator.compileTimeInheritance())
			{
				rootModel.setParentSchema(null);
				rootModel.setParent(null);
				rootModel.setParentModel(null);
				rootModel.getVendorExtensions().put("x-parent-vars", null);

				boolean presentDiscriminatorVar = rootModel
						.getVars()
						.stream()
						.anyMatch(codegenProperty -> codegenProperty.getBaseName().equals(discriminatorPropertyName));

				// there isn't discriminator property => add from discriminator model
				if (!presentDiscriminatorVar)
				{
					String msg = String.format("The discriminator model: %s doesn't have a discriminator property: %s",
							discriminatorModel, discriminatorPropertyName);

					CodegenProperty discriminatorVar = discriminatorModel
							.getRequiredVars()
							.stream()
							.filter(it -> it.getBaseName().equals(discriminatorPropertyName))
							.findFirst()
							.orElseThrow(() -> new IllegalStateException(msg));

					CodegenProperty discriminatorVarCloned = discriminatorVar.clone();
					discriminatorVarCloned.hasMore = false;

					rootModel.getVars().add(discriminatorVarCloned);
					rootModel.getRequiredVars().add(discriminatorVarCloned);
					rootModel.getReadWriteVars().add(discriminatorVarCloned);
					rootModel.getAllVars().add(discriminatorVarCloned);
				}
			}
		}

		// remove discriminator property from inherited Discriminator
		if (discriminatorModel != base && discriminatorModel != schema)
		{
			if (generator.compileTimeInheritance() && base.getDiscriminator() == null)
			{
				return;
			}

			discriminatorModel
					.getRequiredVars()
					.removeIf(codegenProperty -> codegenProperty.getBaseName().equals(discriminatorPropertyName));
			discriminatorModel
					.getAllVars()
					.removeIf(codegenProperty -> codegenProperty.getBaseName().equals(discriminatorPropertyName));
			discriminatorModel.setDiscriminator(null);
		}
	}

	private void setToParentVars(final CodegenModel model, final List<CodegenProperty> parentVars)
	{
		model.setParentVars(cloneVars(parentVars));
		removerReadonlyParentVars(model);
	}

	private void removerReadonlyParentVars(final CodegenModel model)
	{
		model.getParentVars().removeIf(codegenProperty -> model.getReadOnlyVars().stream()
				.anyMatch(parent -> parent.getName().equals(codegenProperty.getName())));
	}

	private void setReadWriteWars(final CodegenModel model, final List<CodegenProperty> parentVars)
	{
		Set<String> readWriteVars = model.getReadWriteVars().stream()
				.map(CodegenProperty::getName)
				.collect(Collectors.toSet());
		cloneVars(parentVars).forEach(codegenProperty -> {
			if (readWriteVars.contains(codegenProperty.getName()))
			{
				return;
			}
			model.getReadWriteVars().add(codegenProperty);
		});
	}

	private void setExtensionParentVars(final CodegenModel model, final List<CodegenProperty> vars)
	{
		setExtensionParentVars(model, model.getParentModel(), vars);
	}

	private void setExtensionParentVars(final CodegenModel model, final CodegenModel parentModel, final List<CodegenProperty> vars)
	{
		List<CodegenProperty> clonedVars = cloneVars(vars);

		model.vendorExtensions.put("x-has-parent-vars", !clonedVars.isEmpty());
		model.vendorExtensions.put("x-parent-vars", clonedVars);
		model.vendorExtensions.put("x-parent-classFilename", parentModel.getClassFilename());
	}

	@NotNull
	private List<CodegenProperty> cloneVars(final List<CodegenProperty> vars)
	{
		List<CodegenProperty> clonedVars = new ArrayList<>();
		vars.forEach(codegenProperty -> {
			CodegenProperty cloned = codegenProperty.clone();
			cloned.hasMore = vars.indexOf(codegenProperty) != vars.size() - 1;
			clonedVars.add(cloned);
		});
		return clonedVars;
	}

	private String[] getDiscriminatorKeys(final Schema schema, final Schema refSchema)
	{
		List<String> keys = new ArrayList<>();

		if (refSchema.getProperties() == null)
		{
			keys.add(schema.getDiscriminator().getPropertyName());
		}
		else
		{
			refSchema.getProperties().forEach((BiConsumer<String, Schema>) (property, propertySchema) -> {

				if (keys.isEmpty())
				{
					keys.add(property);

				}
				else if (propertySchema.getEnum() != null && propertySchema.getEnum().size() == 1)
				{
					keys.add(property);
				}
			});
		}

		return keys.toArray(new String[0]);
	}

	private List<Schema> getOneOf(final Schema schema, final Map<String, Schema> allDefinitions)
	{

		List<Schema> schemas = new ArrayList<>();

		if (schema instanceof ComposedSchema)
		{

			ComposedSchema composedSchema = (ComposedSchema) schema;
			for (Schema oneOfSchema : composedSchema.getOneOf())
			{

				if (oneOfSchema.get$ref() != null)
				{

					Schema refSchema = allDefinitions.get(ModelUtils.getSimpleRef(oneOfSchema.get$ref()));
					if (refSchema instanceof ComposedSchema && ((ComposedSchema) refSchema).getOneOf() != null)
					{
						schemas.addAll(((ComposedSchema) refSchema).getOneOf());
					}
					else
					{
						schemas.add(oneOfSchema);
					}
				}
			}
		}

		return schemas;
	}

	@javax.annotation.Nullable
	private CodegenProperty getCodegenProperty(final CodegenModel model, final String propertyName)
	{
		return model.vars.stream()
				.filter(property -> property.baseName.equals(propertyName))
				.findFirst().orElse(null);
	}

	private List<Schema> getObjectSchemas(final Schema schema, final Map<String, Schema> allDefinitions)
	{
		if (schema instanceof ObjectSchema)
		{
			return Lists.newArrayList(schema);
		}
		else if (schema instanceof ComposedSchema)
		{
			List<Schema> allOf = ((ComposedSchema) schema).getAllOf();
			if (allOf != null)
			{
				return allOf.stream().map(it -> getObjectSchemas(it, allDefinitions))
						.flatMap(Collection::stream)
						.collect(Collectors.toList());
			}
		}
		else if (schema.get$ref() != null)
		{
			return Lists.newArrayList(allDefinitions.get(ModelUtils.getSimpleRef(schema.get$ref())));
		}
		return Lists.newArrayList();
	}

	public class TypeAdapter
	{

		public String classname;
		public String discriminator;
		public boolean isArray;
		public List<PostProcessHelper.TypeAdapterItem> items = new ArrayList<>();
	}

	public class TypeAdapterItem
	{
		public String discriminatorValue;
		public String classname;
	}
}
