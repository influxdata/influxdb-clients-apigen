/*
 * The MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.influxdb.codegen;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.languages.JavaClientCodegen;
import org.openapitools.codegen.utils.ModelUtils;

public class InfluxJavaGenerator extends JavaClientCodegen implements InfluxGenerator
{
	private PostProcessHelper postProcessHelper;

	public InfluxJavaGenerator()
	{

		super();

		importMapping.put("JSON", "com.influxdb.client.JSON");
		importMapping.put("JsonDeserializer", "com.google.gson.JsonDeserializer");
		importMapping.put("JsonDeserializationContext", "com.google.gson.JsonDeserializationContext");
		importMapping.put("JsonSerializationContext", "com.google.gson.JsonSerializationContext");
		importMapping.put("JsonSerializer", "com.google.gson.JsonSerializer");
		importMapping.put("JsonParseException", "com.google.gson.JsonParseException");
		importMapping.put("ArrayList", "java.util.ArrayList");
		importMapping.put("List", "java.util.List");
		importMapping.put("JsonObject", "com.google.gson.JsonObject");
		importMapping.put("JsonArray", "com.google.gson.JsonArray");
		importMapping.put("JsonElement", "com.google.gson.JsonElement");
		importMapping.put("HashMap", "java.util.HashMap");
		importMapping.put("Map", "java.util.Map");
		importMapping.put("ReflectType", "java.lang.reflect.Type");

		//
		// File is mapped to schema not to java.io.File
		//
		importMapping.remove("File");

		setUseNullForUnknownEnumValue(true);
	}

	/**
	 * Configures a friendly name for the generator.  This will be used by the generator to select the library with the
	 * -g flag.
	 *
	 * @return the friendly name for the generator
	 */
	public String getName()
	{
		return "influx-java";
	}

	/**
	 * Returns human-friendly help for the generator.  Provide the consumer with help tips, parameters here
	 *
	 * @return A string value for the help message
	 */
	public String getHelp()
	{
		return "Generates a influx-java client library.";
	}

	@Override
	public void setGlobalOpenAPI(final OpenAPI openAPI)
	{

		super.setGlobalOpenAPI(openAPI);

		postProcessHelper = new PostProcessHelper(this);
		postProcessHelper.postProcessOpenAPI();

		//
		// Set String as body for Writes
		//
		{
			openAPI.getPaths()
					.get("/write")
					.getPost()
					.getRequestBody()
					.getContent()
					.get("text/plain")
					.setSchema(new StringSchema());
		}

		//
		// Use first ResponseObject for operation with multiple types of Response
		//
		openAPI.getPaths().values()
				.forEach(pathItem -> pathItem
						.readOperations()
						.forEach(operation -> operation.getResponses().values().forEach((apiResponse) -> {
							Content content = apiResponse.getContent();
							if (content != null)
							{
								MediaType mediaType = content.get("application/json");
								if (mediaType != null)
								{
									Schema contentSchema = mediaType.getSchema();
									if (contentSchema instanceof ComposedSchema)
									{
										ComposedSchema schema = (ComposedSchema) contentSchema;
										mediaType.setSchema(schema.getOneOf().get(0));
									}
								}
							}
						})));

		//
		// Use Integer instead Long for RetentionRule everySeconds
		//
		{
			Arrays.asList("PatchRetentionRule", "RetentionRule")
					.forEach(schema -> {
						IntegerSchema everySeconds = (IntegerSchema) openAPI.getComponents().getSchemas().get(schema)
								.getProperties()
								.get("everySeconds");
						everySeconds.setFormat(null);
					});
		}
	}

	@Override
	public Map<String, Object> postProcessAllModels(final Map<String, Object> models)
	{

		Map<String, Object> allModels = super.postProcessAllModels(models);
		postProcessHelper.postProcessModels(allModels);

		return allModels;
	}

	@Override
	public void processOpts()
	{

		super.processOpts();

		//
		// We want to use only the JSON.java
		//
		supportingFiles = supportingFiles.stream()
				.filter(supportingFile -> supportingFile.destinationFilename.equals("JSON.java"))
				.collect(Collectors.toList());
	}

	@Override
	public Map<String, Object> postProcessOperationsWithModels(final Map<String, Object> objs,
															   final List<Object> allModels)
	{
		Map<String, Object> operationsWithModels = super.postProcessOperationsWithModels(objs, allModels);

		List<CodegenOperation> operations = (List<CodegenOperation>) ((HashMap) operationsWithModels
				.get("operations"))
				.get("operation");

        //
        // For operations with more response type (Accept) generate additional implementation
        //
		List<CodegenOperation> operationToSplit = operations.stream()
				.filter(operation -> operation.produces.size() > 1)
				.collect(Collectors.toList());

		operationToSplit.forEach(operation -> {

			List<String> returnTypes = operation.produces.stream()
					.filter(produce -> operation.produces.indexOf(produce) != 0)
					.map(produce -> {

						PathItem path = globalOpenAPI.getPaths().get(StringUtils.substringAfter(operation.path, "/v2"));

						Operation apiOperation;
						switch (operation.httpMethod.toLowerCase())
						{
							case "get":
								apiOperation = path.getGet();
								break;
							case "post":
								apiOperation = path.getPost();
								break;
							default:
								throw new IllegalStateException();
						}

						Content content = apiOperation.getResponses().get("200").getContent();
						MediaType mediaType = content.get(produce.get("mediaType"));
						if (mediaType == null)
						{
							return "";
						}
						Schema responseSchema = mediaType.getSchema();

						if (responseSchema.get$ref() != null)
						{

							String modelName = ModelUtils.getSimpleRef(responseSchema.get$ref());

							CodegenModel model = (CodegenModel) ((HashMap) allModels.stream()
									.filter(it -> modelName.equals(((CodegenModel) ((HashMap) it).get("model")).name))
									.findFirst()
									.get()).get("model");

							return model.classname;
						}
						else
						{
							return org.openapitools.codegen.utils.StringUtils.camelize(responseSchema.getType());
						}

					})
					.filter(it -> !it.isEmpty())
					.distinct()
					.collect(Collectors.toList());

			if (!returnTypes.isEmpty())
			{
				returnTypes.add("ResponseBody");
			}

			returnTypes.forEach(returnType -> {
				CodegenOperation codegenOperation = new CodegenOperation();
				codegenOperation.baseName = operation.baseName + returnType;
				codegenOperation.summary = operation.summary;
				codegenOperation.notes = operation.notes;
				codegenOperation.allParams = operation.allParams;
				codegenOperation.httpMethod = operation.httpMethod;
				codegenOperation.path = operation.path;
				codegenOperation.returnType = returnType;
				codegenOperation.operationId = operation.operationId + returnType;

				operations.add(operations.indexOf(operation) + 1, codegenOperation);
			});
		});

		return operationsWithModels;
	}

	@Override
	public CodegenOperation fromOperation(String path, String httpMethod, Operation operation, Map<String, Schema> definitions, OpenAPI openAPI)
	{

		CodegenOperation op = super.fromOperation(path, httpMethod, operation, definitions, openAPI);
		postProcessHelper.postProcessOperation(path, operation, op, definitions);

		return op;
	}


	@Override
	public CodegenModel fromModel(final String name, final Schema schema, final Map<String, Schema> allDefinitions)
	{

		CodegenModel model = super.fromModel(name, schema, allDefinitions);
		postProcessHelper.postProcessModel(model, schema, allDefinitions);

		return model;
	}

	@Override
	public String toApiName(String name)
	{

		if (name.length() == 0)
		{
			return super.toApiName(name);
		}

		//
		// Rename "Api" to "Service"
		//
		return org.openapitools.codegen.utils.StringUtils.camelize(name) + "Service";
	}

	@Override
	public String toModelName(final String name)
	{
		String modelName = super.toModelName(name);

		if ("RetentionRule".equals(modelName))
		{
			return "BucketRetentionRules";
		}

		if ("Resource".equals(modelName))
		{
			return "PermissionResource";
		}

		if ("UserResponse".equals(modelName))
		{
			return "User";
		}

		if ("User".equals(modelName))
		{
			return "PostUser";
		}

		if ("ResourceMembersLinks".equals(modelName))
		{
			return "UsersLinks";
		}

		if ("UserResponseLinks".equals(modelName))
		{
			return "UserLinks";
		}

		if ("DashboardWithViewPropertiesLinks".equals(modelName))
		{
			return "DashboardLinks";
		}

		if ("DashboardWithViewPropertiesMeta".equals(modelName))
		{
			return "DashboardMeta";
		}

		if ("PatchDashboardRequest1".equals(modelName))
		{
			return "PatchDashboardRequest";
		}

		return modelName;
	}

	@NotNull
	@Override
	public OpenAPI getOpenAPI()
	{
		return globalOpenAPI;
	}

	@Override
	public String toEnumConstructorDefaultValue(final String value, final String datatype)
	{
		return toEnumDefaultValue(value.toUpperCase(), datatype);
	}

	@Nullable
	@Override
	public String optionalDatatypeKeyword()
	{
		return null;
	}

	@Override
	public boolean compileTimeInheritance()
	{
		return true;
	}

	@NotNull
	@Override
	public Collection<String> getTypeAdapterImports()
	{
		return Arrays.asList("JsonDeserializer",
				"JsonDeserializationContext",
				"JsonSerializer",
				"JsonSerializationContext",
				"ArrayList",
				"List",
				"JsonElement",
				"JsonObject",
				"JsonArray",
				"JsonParseException",
				"ReflectType");
	}
}