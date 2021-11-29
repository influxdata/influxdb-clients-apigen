package com.influxdb.codegen;

import javax.annotation.Nonnull;

import org.openapitools.codegen.languages.CSharpNetCoreClientCodegen;

/**
 * @author Jakub Bednar (29/11/2021 8:49)
 */
public class InfluxCSharpV2Generator extends CSharpNetCoreClientCodegen
{
	@Nonnull
	public String getName()
	{
		return "influx-csharp";
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
}
