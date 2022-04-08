package com.influxdb;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.yaml.snakeyaml.Yaml;

/**
 * @author Jakub Bednar (04/08/2022 10:16)
 */
public class AppendCustomDefinitions
{
	private static final Logger LOG = Logger.getLogger(AppendCustomDefinitions.class.getName());

	public static void main(String[] args) throws Exception
	{
		if (args.length < 2)
		{
			LOG.info("You have to specify paths for 'oss.yml' and requested definition e.g. '--write-consistency'.");
			return;
		}

		Yaml yaml = new Yaml();
		Map<String, Object> oss = yaml.load(new FileInputStream(args[0]));
		if (Arrays.asList(args).contains("--write-consistency"))
		{
			// create WriteConsistency schema
			LinkedHashMap<String, Object> writeConsistencySchema = new LinkedHashMap<>();
			writeConsistencySchema.put("type", "string");
			writeConsistencySchema.put("enum", Arrays.asList("any","one","quorum","all"));

			Map<String, Object> schemas = AppendCloudDefinitions.mapValue(new String[]{"components", "schemas"}, oss);
			schemas.put("WriteConsistency", writeConsistencySchema);
			List<Map<String, Object>> parameters = AppendCloudDefinitions.mapValue(new String[]{"paths", "/write", "post", "parameters"}, oss);

			// append WriteConsistency parameter into /write
			boolean present = parameters.stream().anyMatch(it -> "consistency".equals(it.get("name")));
			if (!present)
			{
				LinkedHashMap<String, Object> writeConsistencyParam = new LinkedHashMap<>();
				writeConsistencyParam.put("in", "query");
				writeConsistencyParam.put("name", "consistency");
				writeConsistencyParam.put("description", "Sets the write consistency for the point. InfluxDB assumes that the write consistency is 'one' if you do not specify. Available with InfluxDB Enterprise clusters only.");
				writeConsistencyParam.put("schema", Collections.singletonMap("$ref", "#/components/schemas/WriteConsistency"));

				parameters.add(writeConsistencyParam);
			}
			
			LOG.info("Appended 'write-consistency' to: " + args[0]);
		}

		// write to output
		yaml.dump(oss, new FileWriter(args[0]));
	}
}
