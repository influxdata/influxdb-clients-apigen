package com.influxdb.codegen;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Collection;

import org.openapitools.codegen.CodegenProperty;

/**
 * @author Jakub Bednar (08/06/2021 15:39)
 */
public interface InfluxGenerator
{
	@Nonnull
	io.swagger.v3.oas.models.OpenAPI getOpenAPI();

	String toEnumConstructorDefaultValue(String value, String datatype);

	@Nullable
	String optionalDatatypeKeyword();

	void updateCodegenPropertyEnum(CodegenProperty codegenProperty);

	boolean compileTimeInheritance();

	boolean usesOwnAuthorizationSchema();

	@Nonnull
	Collection<String> getTypeAdapterImports();
}
