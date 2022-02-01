package com.influxdb;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.yaml.snakeyaml.Yaml;

/**
 * @author Jakub Bednar (19/10/2021 10:16)
 */
public class AppendCloudDefinitions
{
	private static final Logger LOG = Logger.getLogger(AppendCloudDefinitions.class.getName());

	public static void main(String[] args) throws Exception
	{
		if (args.length != 2)
		{
			LOG.info("You have to specified to path. First to oss.yml and second to cloud.yml.");
			return;
		}

		LOG.info(String.format("I will append definition from %s to: %s", args[1], args[0]));

		Yaml yaml = new Yaml();

		Map<String, Object> oss = yaml.load(new FileInputStream(args[0]));
		Map<String, Object> cloud = yaml.load(new FileInputStream(args[1]));
		List<String> ossPermissionTypes = mapValue(
				new String[]{"components", "schemas", "Resource", "properties", "type", "enum"},
				oss);
		List<String> cloudPermissionTypes = mapValue(
				new String[]{"components", "schemas", "Resource", "properties", "type", "enum"},
				cloud);

		for (String cloudPermissionType : cloudPermissionTypes)
		{
			if (!ossPermissionTypes.contains(cloudPermissionType))
			{
				ossPermissionTypes.add(cloudPermissionType);
			}
		}

		// write to output
		yaml.dump(oss, new FileWriter(args[0]));

		LOG.info("Updated: " + args[0]);
	}

	private static <T> T mapValue(String[] paths, Object object)
	{
		if (paths.length == 0)
		{
			//noinspection unchecked
			return (T) object;
		}

		return mapValue(Arrays.copyOfRange(paths, 1, paths.length), ((Map) object).get(paths[0]));
	}
}
