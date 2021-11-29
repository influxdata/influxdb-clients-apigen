package com.influxdb;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.yaml.snakeyaml.Yaml;

/**
 * @author Jakub Bednar (19/10/2021 10:16)
 */
public class MergeContracts
{
	private static final Logger LOG = Logger.getLogger(MergeContracts.class.getName());

	public static void main(String[] args) throws Exception
	{
		if (args.length <= 1)
		{
			LOG.info("Nothing to merge");
			return;
		}

		LOG.info(String.format("I will merge your contracts: %s to: %s", Arrays.toString(args), args[0]));

		Yaml yaml = new Yaml();
		
		Map<String, Object> destination = yaml.load(new FileInputStream(args[0]));
		LinkedHashMap<String, Object> destinationPaths = getMap(destination, "paths");
		LinkedHashMap<String, Object> destinationSchemas = getMap(getMap(destination, "components"), "schemas");
		for (int i = 1; i < args.length; i++)
		{
			Map<String, Object> toAppend = yaml.load(new FileInputStream(args[i]));

			// paths
			LinkedHashMap<String, Object> toAppendPaths = getMap(toAppend, "paths");
			toAppendPaths.forEach(destinationPaths::putIfAbsent);

			// schemas
			LinkedHashMap<String, Object> toAppendSchemas = getMap(getMap(toAppend, "components"), "schemas");
			toAppendSchemas.forEach(destinationSchemas::putIfAbsent);
		}

		// write to output
		yaml.dump(destination, new FileWriter(args[0]));

		LOG.info("Constracts merged into: " + args[0]);
	}

	private static LinkedHashMap<String, Object> getMap(final Map<String, Object> destination, final String paths)
	{
		//noinspection unchecked
		return (LinkedHashMap<String, Object>) destination.get(paths);
	}
}
