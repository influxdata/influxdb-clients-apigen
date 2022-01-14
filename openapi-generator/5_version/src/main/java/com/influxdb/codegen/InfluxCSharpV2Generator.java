package com.influxdb.codegen;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.languages.CSharpNetCoreClientCodegen;
import org.openapitools.codegen.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Jakub Bednar (29/11/2021 8:49)
 */
public class InfluxCSharpV2Generator extends CSharpNetCoreClientCodegen implements InfluxGenerator
{
	private static final Logger LOG = LoggerFactory.getLogger(InfluxCSharpV2Generator.class);

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

		// Body of Write is set to String
		openAPI.getPaths().get("/write")
				.readOperations()
				.forEach(operation -> {
					MediaType mediaType = operation.getRequestBody().getContent().get("text/plain");
					mediaType.setSchema(new StringSchema().type("string"));
				});
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
		additionalProperties.put(SUPPORTS_RETRY, false);

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
	public Map<String, Object> postProcessOperationsWithModels(final Map<String, Object> objs, final List<Object> allModels)
	{
		super.postProcessOperationsWithModels(objs, allModels);
		// add custom import for Authorization - there is collision with "System.Net.Authorization"
		Object serviceClassName = ((Map) objs.get("operations")).get("classname");
		if (Arrays.asList("AuthorizationsService", "LegacyAuthorizationsService").contains(serviceClassName))
		{
			objs.put("customImports", Arrays.asList("using Authorization = InfluxDB.Client.Api.Domain.Authorization;"));
		}

		return objs;
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

	@Override
	public void postProcess()
	{
		super.postProcess();

		File srcFolder = new File(outputFolder() + File.separator + "InfluxDB.Client.Api/Client");
		File dstFolder = new File(outputFolder() + File.separator + "../Client.Core/Api");

		try
		{
			// delete existing sources
			FileUtils.deleteDirectory(dstFolder);
			// move generated
			LOG.info("move " + srcFolder + " -> " + dstFolder);
			FileUtils.moveDirectory(srcFolder, dstFolder);
			FileUtils.moveFileToDirectory(new File(outputFolder + File.separator + "InfluxDB.Client.Api/Domain/AbstractOpenAPISchema.cs"), dstFolder, false);
			
			// change namespace
			List<File> folders = Arrays.asList(dstFolder,
					new File(outputFolder() + File.separator + "InfluxDB.Client.Api/Service"),
					new File(outputFolder() + File.separator + "InfluxDB.Client.Api/Domain"));
			for (File folder : folders)
			{
				LOG.info("replace namespace for files in folder " + folder);
				for (File file : FileUtils.listFiles(folder, new String[]{"cs"}, false))
				{
					String fileString = FileUtils.readFileToString(file, "UTF-8");
					String finalString = fileString.replaceAll("InfluxDB.Client.Api.Client", "InfluxDB.Client.Core.Api");
					FileUtils.writeStringToFile(file, finalString, "UTF-8");
				}

			}
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
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
