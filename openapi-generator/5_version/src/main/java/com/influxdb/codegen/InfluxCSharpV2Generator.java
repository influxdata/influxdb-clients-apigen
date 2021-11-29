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
import io.swagger.v3.oas.models.servers.Server;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.languages.CSharpNetCoreClientCodegen;
import org.openapitools.codegen.utils.ModelUtils;

/**
 * @author Jakub Bednar (29/11/2021 8:49)
 */
public class InfluxCSharpV2Generator extends CSharpNetCoreClientCodegen implements InfluxGenerator
{
	private PostProcessHelper postProcessHelper;

	@Nonnull
	public String getName()
	{
		return "influx-csharp";
	}

	@Override
	public void setOpenAPI(final OpenAPI openAPI)
	{

		super.setOpenAPI(openAPI);

		postProcessHelper = new PostProcessHelper(this);
		postProcessHelper.postProcessOpenAPI();
	}

	@Override
	public CodegenModel fromModel(final String name, final Schema model)
	{
		CodegenModel codegenModel = super.fromModel(name, model);
		postProcessHelper.postProcessModel(codegenModel, model, ModelUtils.getSchemas(this.openAPI));

		return codegenModel;

	}

	@Override
	public CodegenOperation fromOperation(final String path, final String httpMethod, final Operation operation, final List<Server> servers)
	{
		CodegenOperation op = super.fromOperation(path, httpMethod, operation, servers);
		postProcessHelper.postProcessOperation(path, operation, op, ModelUtils.getSchemas(this.openAPI));

		return op;
	}

	@Override
	public void processOpts()
	{

		super.processOpts();

		List<String> accepted = Arrays.asList(
				"ApiResponse.cs", "OpenAPIDateConverter.cs", "ExceptionFactory.cs",
				"Configuration.cs", "ApiException.cs", "IApiAccessor.cs", "ApiClient.cs",
				"IReadableConfiguration.cs", "GlobalConfiguration.cs", "FileParameter.cs",
				"IAsynchronousClient.cs", "ISynchronousClient.cs", "AbstractOpenAPISchema.cs",
				"Multimap.cs", "RequestOptions.cs", "ClientUtils.cs", "WebRequestPathBuilder.cs");

		//
		// We want to use only the JSON.java
		//
		supportingFiles = supportingFiles.stream()
				.filter(supportingFile -> accepted.contains(supportingFile.getDestinationFilename()))
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
		return org.openapitools.codegen.utils.StringUtils.camelize(name) + "Service";
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
		return openAPI;
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
