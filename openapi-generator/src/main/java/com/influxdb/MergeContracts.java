package com.influxdb;

import java.io.FileInputStream;
import java.util.Arrays;
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
		if (args.length <= 1) {
			LOG.info("Nothing to merge");
		}

		LOG.info(String.format("I will merge your contracts: %s to: %s", Arrays.toString(args), args[0]));

		Yaml yaml = new Yaml();
		Map<String, Object> destination = yaml.load(new FileInputStream(args[0]));
		System.out.println(destination.keySet());
		for (int i = 1; i < args.length; i++)
		{
			Map<String, Object> toAppend = yaml.load(new FileInputStream(args[i]));
			System.out.println(toAppend.keySet());
		}
	}
}
