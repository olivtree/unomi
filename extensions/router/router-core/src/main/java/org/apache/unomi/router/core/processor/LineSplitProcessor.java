/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.unomi.router.core.processor;

import com.opencsv.RFC4180Parser;
import com.opencsv.RFC4180ParserBuilder;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.unomi.api.PropertyType;
import org.apache.unomi.api.services.ProfileService;
import org.apache.unomi.router.api.ImportConfiguration;
import org.apache.unomi.router.api.ProfileToImport;
import org.apache.unomi.router.api.RouterConstants;
import org.apache.unomi.router.api.RouterUtils;
import org.apache.unomi.router.core.exception.BadProfileDataFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Created by amidani on 29/12/2016.
 */
public class LineSplitProcessor implements Processor {

    private static final Logger logger = LoggerFactory.getLogger(LineSplitProcessor.class.getName());

    private Map<String, Integer> fieldsMapping;
    private List<String> propertiesToOverwrite;
    private String mergingProperty;
    private boolean overwriteExistingProfiles;
    private boolean hasHeader;
    private boolean hasDeleteColumn;
    private String columnSeparator;

    private String multiValueSeparator;
    private String multiValueDelimiter;

    private ProfileService profileService;

    @Override
    public void process(Exchange exchange) throws Exception {

        //In case of one shot import we check the header and overwrite import config
        ImportConfiguration importConfigOneShot = (ImportConfiguration) exchange.getIn().getHeader(RouterConstants.HEADER_IMPORT_CONFIG_ONESHOT);
        String configType = (String) exchange.getIn().getHeader(RouterConstants.HEADER_CONFIG_TYPE);
        if (importConfigOneShot != null) {
            fieldsMapping = (Map<String, Integer>) importConfigOneShot.getProperties().get("mapping");
            propertiesToOverwrite = importConfigOneShot.getPropertiesToOverwrite();
            mergingProperty = importConfigOneShot.getMergingProperty();
            overwriteExistingProfiles = importConfigOneShot.isOverwriteExistingProfiles();
            columnSeparator = importConfigOneShot.getColumnSeparator();
            hasHeader = importConfigOneShot.isHasHeader();
            hasDeleteColumn = importConfigOneShot.isHasDeleteColumn();
            multiValueSeparator = importConfigOneShot.getMultiValueSeparator();
            multiValueDelimiter = importConfigOneShot.getMultiValueDelimiter();
        }

        if ((Integer) exchange.getProperty("CamelSplitIndex") == 0 && hasHeader) {
            exchange.setProperty(Exchange.ROUTE_STOP, Boolean.TRUE);
            return;
        }

        RFC4180Parser rfc4180Parser = new RFC4180ParserBuilder()
                .withSeparator(columnSeparator.charAt(0))
                .build();

        String[] profileData = rfc4180Parser.parseLine(((String) exchange.getIn().getBody()));
        ProfileToImport profileToImport = new ProfileToImport();
        profileToImport.setItemId(UUID.randomUUID().toString());
        profileToImport.setItemType("profile");
        profileToImport.setScope(RouterConstants.SYSTEM_SCOPE);

        if (profileData.length > 0 && StringUtils.isNotBlank(profileData[0])) {
            if (hasDeleteColumn && (fieldsMapping.size() > (profileData.length - 1))) {
                throw new BadProfileDataFormatException("The mapping does not match the number of column : line [" + ((Integer) exchange.getProperty("CamelSplitIndex") + 1) + "]", new Throwable("MAPPING_COLUMN_MATCH"));
            }
            Map<String, Object> properties = new HashMap<>();
            for (String fieldMappingKey : fieldsMapping.keySet()) {
                PropertyType propertyType = RouterUtils.getPropertyTypeById(profileService.getAllPropertyTypes("profiles"), fieldMappingKey);

                if (profileData.length > fieldsMapping.get(fieldMappingKey)) {
                    try {
                        if (propertyType.getValueTypeId().equals("string") || propertyType.getValueTypeId().equals("email")) {
                            if (BooleanUtils.isTrue(propertyType.isMultivalued())) {
                                String multivalueArray = profileData[fieldsMapping.get(fieldMappingKey)].trim();
                                if (StringUtils.isNoneBlank(multiValueDelimiter) && multiValueDelimiter.length() == 2) {
                                    multivalueArray = multivalueArray.replaceAll("\\" + multiValueDelimiter.charAt(0), "").replaceAll("\\" + multiValueDelimiter.charAt(1), "");
                                    multivalueArray = RouterUtils.removeQuotes(multivalueArray);
                                }
                                String[] valuesArray = multivalueArray.split("\\" + multiValueSeparator);
                                properties.put(fieldMappingKey, valuesArray);
                            } else {
                                String singleValue = profileData[fieldsMapping.get(fieldMappingKey)].trim();
                                properties.put(fieldMappingKey, RouterUtils.removeQuotes(singleValue));
                            }
                        } else if (propertyType.getValueTypeId().equals("boolean")) {
                            properties.put(fieldMappingKey, new Boolean(profileData[fieldsMapping.get(fieldMappingKey)].trim()));
                        } else if (propertyType.getValueTypeId().equals("integer")) {
                            properties.put(fieldMappingKey, new Integer(profileData[fieldsMapping.get(fieldMappingKey)].trim()));
                        }
                    } catch (Exception e) {
                        throw new BadProfileDataFormatException("Unable to convert '" + profileData[fieldsMapping.get(fieldMappingKey)].trim() + "' to " + propertyType.getValueTypeId(), new Throwable("DATA_TYPE"));
                    }

                }
            }
            profileToImport.setProperties(properties);
            profileToImport.setMergingProperty(mergingProperty);
            profileToImport.setPropertiesToOverwrite(propertiesToOverwrite);
            profileToImport.setOverwriteExistingProfiles(overwriteExistingProfiles);
            if (hasDeleteColumn && StringUtils.isNotBlank(profileData[profileData.length - 1]) &&
                    Boolean.parseBoolean(profileData[profileData.length - 1].trim())) {
                profileToImport.setProfileToDelete(true);
            }
        } else {
            throw new BadProfileDataFormatException("Empty line : line [" + ((Integer) exchange.getProperty("CamelSplitIndex") + 1) + "]", new Throwable("EMPTY_LINE"));
        }
        exchange.getIn().setBody(profileToImport, ProfileToImport.class);
        if (RouterConstants.CONFIG_TYPE_KAFKA.equals(configType)) {
            exchange.getIn().setHeader(KafkaConstants.PARTITION_KEY, 0);
            exchange.getIn().setHeader(KafkaConstants.KEY, "1");
        }
    }

    /**
     * Setter of fieldsMapping
     *
     * @param fieldsMapping map String,Integer fieldName in ES and the matching column index in the import file
     */
    public void setFieldsMapping(Map<String, Integer> fieldsMapping) {
        this.fieldsMapping = fieldsMapping;
    }

    public void setPropertiesToOverwrite(List<String> propertiesToOverwrite) {
        this.propertiesToOverwrite = propertiesToOverwrite;
    }

    public void setOverwriteExistingProfiles(boolean overwriteExistingProfiles) {
        this.overwriteExistingProfiles = overwriteExistingProfiles;
    }

    public void setHasHeader(boolean hasHeader) {
        this.hasHeader = hasHeader;
    }

    public void setHasDeleteColumn(boolean hasDeleteColumn) {
        this.hasDeleteColumn = hasDeleteColumn;
    }

    /**
     * Sets the merging property.
     *
     * @param mergingProperty property used to check if the profile exist when merging
     */
    public void setMergingProperty(String mergingProperty) {
        this.mergingProperty = mergingProperty;
    }

    /**
     * Sets the line separator.
     *
     * @param columnSeparator property used to specify a line separator. Defaults to ','
     */
    public void setColumnSeparator(String columnSeparator) {
        this.columnSeparator = columnSeparator;
    }

    /**
     * Sets the multi value separator.
     *
     * @param multiValueSeparator multi value separator
     */
    public void setMultiValueSeparator(String multiValueSeparator) {
        this.multiValueSeparator = multiValueSeparator;
    }

    /**
     * Sets the multi value delimiter.
     *
     * @param multiValueDelimiter multi value delimiter
     */
    public void setMultiValueDelimiter(String multiValueDelimiter) {
        this.multiValueDelimiter = multiValueDelimiter;
    }

    /**
     * Sets the Profile service.
     *
     * @param profileService Profile service
     */
    public void setProfileService(ProfileService profileService) {
        this.profileService = profileService;
    }

}
