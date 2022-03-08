package com.influxdb.codegen;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.languages.PythonClientCodegen;
import org.openapitools.codegen.utils.StringUtils;

public class InfluxPythonGenerator extends PythonClientCodegen implements InfluxGenerator  {

	private PostProcessHelper postProcessHelper;

    public InfluxPythonGenerator() {
        apiPackage = "service";
        modelPackage = "domain";
    }

    /**
     * Configures a friendly name for the generator.  This will be used by the generator
     * to select the library with the -g flag.
     *
     * @return the friendly name for the generator
     */
    public String getName() {
        return "influx-python";
    }

    /**
     * Returns human-friendly help for the generator.  Provide the consumer with help
     * tips, parameters here
     *
     * @return A string value for the help message
     */
    public String getHelp() {
        return "Generates a influx-python client library.";
    }

	@Override
	public void setGlobalOpenAPI(final OpenAPI openAPI)
	{
		super.setGlobalOpenAPI(openAPI);

		postProcessHelper = new PostProcessHelper(this)
				.addPreferredSchemaForMultipleResponseType("GetDashboardsID", "DashboardWithViewProperties");
		postProcessHelper.postProcessOpenAPI();
	}

	@Override
	public CodegenOperation fromOperation(String path, String httpMethod, Operation operation, Map<String, Schema> definitions, OpenAPI openAPI) {

		CodegenOperation op = super.fromOperation(path, httpMethod, operation, definitions, openAPI);
		postProcessHelper.postProcessOperation(path, operation, op, definitions);
		if ("/ping".equals(path) && "get".equals(httpMethod)) {
			op.vendorExtensions.put("x-async-enabled", true);
		}
		if ("/query".equals(path)) {
			op.vendorExtensions.put("x-async-enabled", true);
		}
		if ("/write".equals(path)) {
			op.vendorExtensions.put("x-async-enabled", true);
		}
		if ("/delete".equals(path)) {
			op.vendorExtensions.put("x-async-enabled", true);
		}

		return op;
	}

	@Override
    public void processOpts() {

        super.processOpts();

        List<String> useless = Arrays.asList(
                ".gitignore", ".travis.yml", "README.md", "setup.py", "requirements.txt", "test-requirements.txt",
                "tox.ini", "git_push.sh", "api_client.py", "rest.py");

        //
        // Remove useless supports file
        //
        supportingFiles = supportingFiles.stream()
                .filter(supportingFile -> !useless.contains(supportingFile.destinationFilename))
                .collect(Collectors.toList());

		supportingFiles.add(new SupportingFile("api_client.mustache", packageName  + File.separatorChar + "_sync", "api_client.py"));
		supportingFiles.add(new SupportingFile("rest.mustache", packageName  + File.separatorChar + "_sync", "rest.py"));

		supportingFiles.add(new SupportingFile("api_client_async.mustache", packageName  + File.separatorChar + "_async", "api_client.py"));
		supportingFiles.add(new SupportingFile("rest_async.mustache", packageName  + File.separatorChar + "_async", "rest.py"));

		supportingFiles.add(new SupportingFile("rest_commons.mustache", packageName, "rest.py"));
    }

    @Override
    public Map<String, Object> postProcessAllModels(final Map<String, Object> models) {

        Map<String, Object> allModels = super.postProcessAllModels(models);
		postProcessHelper.postProcessModels(allModels);

		return allModels;
    }

	@Override
	public String escapeText(String input) {
		if (input == null) {
			return null;
		}

		return super.escapeText(input).replace("\\\"", "\"");
	}

	@Override
    public String toApiName(String name) {
        if (name.length() == 0) {
            return "DefaultService";
        }
        // e.g. phone_number_service => PhoneNumberService
        return StringUtils.camelize(name) + "Service";
    }

    @Override
    public String toApiVarName(String name) {

        if (name.length() == 0) {
            return "default_service";
        }
        return StringUtils.underscore(name) + "_service";
    }

    @Override
    public String toApiFilename(String name) {
        // replace - with _ e.g. created-at => created_at
        name = name.replaceAll("-", "_");

        // e.g. PhoneNumberService.py => phone_number_service.py
        return StringUtils.underscore(name) + "_service";
    }

	@Override
	public String toVarName(String name)
	{
		name = name.replaceAll("orgIDs", "orgIds");

		return super.toVarName(name);
	}

	@Override
	public String toModelFilename(String name)
	{
		name = name.replaceAll("orgIDs", "orgIds");

		return super.toModelFilename(name);
	}

	@Override
	public void processOpenAPI(OpenAPI openAPI) {

		List<String> serviceInits = Lists.newArrayList(
				"influxdb_client/client/write/__init__.py",
				"influxdb_client/client/__init__.py",
				"tests/__init__.py");
		
		postProcessHelper.copyFiles("influxdb_client/service/__init__.py", serviceInits, this);
	}

	@Override
	public String toModelName(final String name) {
		final String modelName = super.toModelName(name);
		if ("RetentionRule".equals(modelName)) {
			return "BucketRetentionRules";
		}
		if ("Resource".equals(modelName)) {
			return "PermissionResource";
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
		return "\"" + value + "\"";
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
		return false;
	}

	@Override
	public boolean usesOwnAuthorizationSchema()
	{
		return true;
	}

	@Override
	public boolean supportsStacksTemplates()
	{
		return true;
	}

	@Override
	public boolean permissionResourceTypeAsString()
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
