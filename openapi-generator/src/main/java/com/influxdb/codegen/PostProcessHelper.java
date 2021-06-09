package com.influxdb.codegen;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.apache.commons.lang3.StringUtils;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;
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
	private final InfluxGenerator generator;

	public PostProcessHelper(InfluxGenerator generator)
	{
		this.generator = generator;
		this.openAPI = generator.getOpenAPI();
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
		// Use generic schema for Flags
		//
		{
			openAPI.getComponents().getSchemas().put("Flags", new ObjectSchema().additionalProperties(new Schema()));
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
		if (generator.compileTimeInheritance()) {
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

		//
		// Authorization header for operation with Basic Security
		//
		{
			openAPI.getPaths().forEach((path, pathItem) -> {
				pathItem
						.readOperations()
						.forEach(operation -> {
							if (operation != null && operation.getSecurity() != null && !operation.getSecurity().isEmpty())
							{
								boolean containsBasicAuth = operation
										.getSecurity()
										.stream()
										.anyMatch(securityRequirement -> securityRequirement.containsKey("BasicAuth"));

								if (containsBasicAuth)
								{
									Parameter authorization = new HeaderParameter()
											.name("Authorization")
											.schema(new StringSchema())
											.description("An auth credential for the Basic scheme");
									operation.addParametersItem(authorization);
								}
							}
						});
			});
		}

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
	}

	public void postProcessModel(final CodegenModel codegenModel)
	{
		//
		// Set default values for Enum Variables
		//
		codegenModel.getAllVars().stream()
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
					if (interfaceModel.getParent() == null) {
						interfaceModel.setParent(model.classname);
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

		fixInheritance("Check", Arrays.asList("Deadman", "Custom", "Threshold"), allModels);
		fixInheritance("Threshold", Arrays.asList("Greater", "Lesser", "Range"), allModels);
		fixInheritance("NotificationEndpoint", Arrays.asList("Slack", "PagerDuty", "HTTP", "Telegram"), allModels);
		fixInheritance("NotificationRule", Arrays.asList("Slack", "PagerDuty", "SMTP", "HTTP", "Telegram"), allModels);

		// Iterate all models
		for (Map.Entry<String, Object> entry : allModels.entrySet())
		{
			CodegenModel model = getModel((HashMap) entry.getValue());
			String modelName = model.getName();

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
	}

	@Nonnull
	CodegenModel getModel(@Nonnull final HashMap modelConfig)
	{

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
			}

			if (discriminatorModelBase != discriminatorModel)
			{
				discriminatorModelBase.setParentModel(discriminatorModel);
				discriminatorModelBase.setParent(discriminatorModel.getName());
				discriminatorModelBase.setParentSchema(discriminatorModel.getName());
				setToParentVars(discriminatorModelBase, discriminatorModel.getParentVars());
				setExtensionParentVars(discriminatorModelBase, base.getVars());
				setReadWriteWars(discriminatorModelBase, discriminatorModel.getParentVars());
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
				// If there is no intermediate entity, than leave current parent schema
				if (allModels.containsKey(name + "Discriminator"))
				{
					rootModel.setParentSchema(null);
					rootModel.setParent(null);
				}

				boolean presentDiscriminatorVar = rootModel
						.getRequiredVars()
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
		if (discriminatorModel != base)
		{
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
}
