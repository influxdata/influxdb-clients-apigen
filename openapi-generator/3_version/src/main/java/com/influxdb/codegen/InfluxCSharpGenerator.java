package com.influxdb.codegen;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.languages.CSharpClientCodegen;

public class InfluxCSharpGenerator extends CSharpClientCodegen implements InfluxGenerator
{

	private PostProcessHelper postProcessHelper;

	public InfluxCSharpGenerator()
	{
		super();

		embeddedTemplateDir = templateDir = "csharp";
	}

	/**
	 * Configures a friendly name for the generator.  This will be used by the generator to select the library with the
	 * -g flag.
	 *
	 * @return the friendly name for the generator
	 */
	@Nonnull
	public String getName()
	{
		return "influx-csharp";
	}

	/**
	 * Returns human-friendly help for the generator.  Provide the consumer with help tips, parameters here
	 *
	 * @return A string value for the help message
	 */
	@Nonnull
	public String getHelp()
	{
		return "Generates a influx-csharp client library.";
	}

	@Override
	public void setGlobalOpenAPI(final OpenAPI openAPI)
	{

		super.setGlobalOpenAPI(openAPI);

		postProcessHelper = new PostProcessHelper(this);
		postProcessHelper.postProcessOpenAPI();
	}

	@Override
	public CodegenModel fromModel(final String name, final Schema schema, final Map<String, Schema> allDefinitions)
	{

		CodegenModel model = super.fromModel(name, schema, allDefinitions);
		postProcessHelper.postProcessModel(model, schema, allDefinitions);

		return model;
	}

	@Override
	public CodegenOperation fromOperation(String path, String httpMethod, Operation operation, Map<String, Schema> definitions, OpenAPI openAPI)
	{

		CodegenOperation op = super.fromOperation(path, httpMethod, operation, definitions, openAPI);
		postProcessHelper.postProcessOperation(path, operation, op, definitions);

		return op;
	}

	@Override
	public void processOpts()
	{

		super.processOpts();

		List<String> accepted = Arrays.asList(
				"ApiResponse.cs", "OpenAPIDateConverter.cs", "ExceptionFactory.cs",
				"Configuration.cs", "ApiException.cs", "IApiAccessor.cs", "ApiClient.cs",
				"IReadableConfiguration.cs", "GlobalConfiguration.cs");

		//
		// We want to use only the JSON.java
		//
		supportingFiles = supportingFiles.stream()
				.filter(supportingFile -> accepted.contains(supportingFile.destinationFilename))
				.collect(Collectors.toList());
	}

	@Override
	public Map<String, Object> postProcessAllModels(final Map<String, Object> models)
	{

		Map<String, Object> allModels = super.postProcessAllModels(models);
		postProcessHelper.postProcessModels(allModels);

		return allModels;
	}

	@Override
	public String toApiName(String name)
	{

		if (name.length() == 0)
		{
			return "DefaultService";
		}

		//
		// Rename "Api" to "Service"
		//
		return initialCaps(name) + "Service";
	}

	@Override
	public String toModelName(final String name)
	{
		final String modelName = super.toModelName(name);
		if ("Task".equals(modelName))
		{
			return "TaskType";
		}

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
		return toEnumDefaultValue(org.openapitools.codegen.utils.StringUtils.camelize(value), datatype);
	}

	@Nullable
	@Override
	public String optionalDatatypeKeyword()
	{
		return "?";
	}

	@Override
	public boolean compileTimeInheritance()
	{
		return true;
	}

	@Override
	public boolean usesOwnAuthorizationSchema()
	{
		return false;
	}

	@NotNull
	@Override
	public Collection<String> getTypeAdapterImports()
	{
		return Collections.emptyList();
	}
}