package com.influxdb.codegen;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.languages.PhpClientCodegen;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.stream.Collectors;

public class InfluxPhpGenerator extends PhpClientCodegen implements InfluxGenerator {

	private PostProcessHelper postProcessHelper;

	public InfluxPhpGenerator() {
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
		return "influx-php";
	}

	/**
	 * Returns human-friendly help for the generator.  Provide the consumer with help
	 * tips, parameters here
	 *
	 * @return A string value for the help message
	 */
	public String getHelp() {
		return "Generates a influx-php client library.";
	}

	@Override
	public void setGlobalOpenAPI(final OpenAPI openAPI)
	{
		super.setGlobalOpenAPI(openAPI);

		postProcessHelper = new PostProcessHelper(this);
		postProcessHelper.postProcessOpenAPI();
	}

	@Override
	public String escapeText(String input) {
		if (input == null) {
			return input;
		}

		return super.escapeText(input).replace("\\\"", "\"");
	}

	@Override
	public CodegenOperation fromOperation(String path, String httpMethod, Operation operation, Map<String, Schema> definitions, OpenAPI openAPI) {

		CodegenOperation op = super.fromOperation(path, httpMethod, operation, definitions, openAPI);
		postProcessHelper.postProcessOperation(path, operation, op, definitions);

		return op;
	}


	@Override
	public void processOpts() {

		super.processOpts();

		List<String> useless = Arrays.asList(".gitignore");

		//
		// Remove useless supports file
		//
		supportingFiles = supportingFiles.stream()
				.filter(supportingFile -> !useless.contains(supportingFile.destinationFilename))
				.collect(Collectors.toList());
	}

	@Override
	public CodegenModel fromModel(final String name, final Schema schema, final Map<String, Schema> allDefinitions)
	{

		CodegenModel model = super.fromModel(name, schema, allDefinitions);
		postProcessHelper.postProcessModel(model, schema, allDefinitions);

		return model;
	}

	@Override
	public Map<String, Object> postProcessAllModels(final Map<String, Object> models) {

		Map<String, Object> allModels = super.postProcessAllModels(models);
		postProcessHelper.postProcessModels(allModels);

		return allModels;
	}

	@Override
	public String toApiName(String name) {
		if (name.length() == 0) {
			return "DefaultService";
		}
		// e.g. phone_number_service => PhoneNumberService
		return camelize(name) + "Service";
	}

	@Override
	public String toApiVarName(String name) {

		if (name.length() == 0) {
			return "default_service";
		}
		return snakeCase(name) + "Service";
	}

	@Override
	public String toApiFilename(String name) {
		// replace - with _ e.g. created-at => created_at
		name = name.replaceAll("-", "_");

		// e.g. PhoneNumberService.py => phone_number_service.py
		return initialCaps(name) + "Service";
	}

	@Override
	public String toModelName(final String name) {
		final String modelName = super.toModelName(name);
		if ("PostBucketRequestRetentionRules".equals(modelName)) {
			return "BucketRetentionRules";
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
		return true;
	}

	@NotNull
	@Override
	public Collection<String> getTypeAdapterImports()
	{
		return Collections.emptyList();
	}
}
