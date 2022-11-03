package com.influxdb;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.yaml.snakeyaml.Yaml;

/**
 * @author Jakub Bednar (19/10/2021 10:16)
 */
public class AppendCloudDefinitions {
    private static final Logger LOG = Logger.getLogger(AppendCloudDefinitions.class.getName());

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            LOG.info("You have to specify paths for 'oss.yml' and 'cloud.yml'.");
            return;
        }

        LOG.info(String.format("I will append definition from %s to: %s", args[1], args[0]));

        Yaml yaml = new Yaml();

        Map<String, Object> oss = yaml.load(new FileInputStream(args[0]));
        Map<String, Object> cloud = yaml.load(new FileInputStream(args[1]));

        // Add Permission Types from Cloud
        {
            String[] permissionTypePath = {"components", "schemas", "Resource", "properties", "type", "enum"};
            List<String> ossPermissionTypes = mapValue(permissionTypePath, oss);
            List<String> cloudPermissionTypes = mapValue(permissionTypePath, cloud);

            for (String cloudPermissionType : cloudPermissionTypes) {
                if (!ossPermissionTypes.contains(cloudPermissionType)) {
                    ossPermissionTypes.add(cloudPermissionType);
                }
            }
        }

        // Add Bucket Explicit Schema API
        {
            LinkedHashMap paths = mapValue(new String[]{"paths"}, oss);
            List<String> pathNames = Arrays.asList(
                    "/buckets/{bucketID}/schema/measurements",
                    "/buckets/{bucketID}/schema/measurements/{measurementID}");
            for (String pathName : pathNames) {
                LinkedHashMap path = mapValue(new String[]{"paths", pathName}, cloud);
                paths.putIfAbsent(pathName, path);
            }

            LinkedHashMap schemas = mapValue(new String[]{"components", "schemas"}, oss);
            List<String> schemaNames = Arrays.asList(
                    "MeasurementSchema",
                    "MeasurementSchemaColumn",
                    "MeasurementSchemaCreateRequest",
                    "MeasurementSchemaList",
                    "MeasurementSchemaUpdateRequest",
                    "ColumnDataType",
                    "ColumnSemanticType");
            for (String schemaName : schemaNames) {
                LinkedHashMap schema = mapValue(new String[]{"components", "schemas", schemaName}, cloud);
                schemas.putIfAbsent(schemaName, schema);
            }
        }

        // write to output
        yaml.dump(oss, new FileWriter(args[0]));

        LOG.info("Updated: " + args[0]);
    }

    protected static <T> T mapValue(String[] paths, Object object) {
        if (paths.length == 0) {
            //noinspection unchecked
            return (T) object;
        }

        return mapValue(Arrays.copyOfRange(paths, 1, paths.length), ((Map) object).get(paths[0]));
    }
}
