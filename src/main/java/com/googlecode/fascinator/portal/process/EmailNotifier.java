/*******************************************************************************
 * Copyright (C) 2013 Queensland Cyber Infrastructure Foundation (http://www.qcif.edu.au/)
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 ******************************************************************************/
package com.googlecode.fascinator.portal.process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

import org.apache.commons.mail.ByteArrayDataSource;
import org.apache.commons.mail.DefaultAuthenticator;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailAttachment;
import org.apache.commons.mail.MultiPartEmail;
import org.apache.commons.mail.SimpleEmail;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.exception.MethodInvocationException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.json.simple.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.googlecode.fascinator.api.indexer.Indexer;
import com.googlecode.fascinator.api.indexer.IndexerException;
import com.googlecode.fascinator.api.indexer.SearchRequest;
import com.googlecode.fascinator.common.JsonObject;
import com.googlecode.fascinator.common.JsonSimple;
import com.googlecode.fascinator.common.StorageDataUtil;
import com.googlecode.fascinator.common.solr.SolrDoc;
import com.googlecode.fascinator.common.solr.SolrResult;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

/**
 * Template-aware email utility class.
 * 
 * 
 * @author Shilo Banihit
 * 
 */
public class EmailNotifier implements Processor {

    private Logger log = LoggerFactory.getLogger(EmailNotifier.class);

    private String host;
    private String port;
    private String tls;
    private String ssl;
    private String username;
    private String password;
    private boolean isVariableNameHiddenIfEmpty;

    /**
     * Initialises this instance.
     * 
     * @param config
     */
    private void init(JsonSimple config) {
        host = config.getString("", "host");
        port = config.getString("", "port");
        username = config.getString("", "username");
        password = config.getString("", "password");
        tls = config.getString("false", "tls");
        ssl = config.getString("false", "ssl");
        isVariableNameHiddenIfEmpty = Boolean.parseBoolean(config.getString("false", "isVariableNameHiddenIfEmpty"));
        Properties props = System.getProperties();

        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", tls);

    }

    /**
     * Replaces any variables in the templates using the mapping specified in
     * the config.
     * 
     * @param solrDoc
     * @param vars
     * @param config
     * @param context
     */
    private void initVars(SolrDoc solrDoc, List<String> vars,
            JsonSimple config, VelocityContext context) {
        for (String var : vars) {
            String varField = config.getString("", "mapping", var);
            String replacement = getCollectionValues(config, solrDoc, varField);
            if (StringUtils.isBlank(replacement)) {
                if (isVariableNameHiddenIfEmpty) {
                    replacement = solrDoc.getString("", varField);
                } else {
                    replacement = solrDoc.getString(var, varField);
                }
            }
            if (replacement == null || "".equals(replacement)) {
                JSONArray arr = solrDoc.getArray(varField);
                if (arr != null) {
                    replacement = (String) arr.get(0);
                    if (replacement == null) {
                        // giving up, setting back to source value so caller can
                        // evaluate
                        if (isVariableNameHiddenIfEmpty) {
                           replacement = "";
                        } else {
                            replacement = var;
                        }
                    }
                } else {
                    // giving up, setting back to source value so caller can
                    // evaluate
                    if (isVariableNameHiddenIfEmpty) {
                        replacement = "";
                    } else {
                        replacement = var;
                    }
                }
            }
            log.debug("Getting variable value '" + var + "' using field '"
                    + varField + "', value:" + replacement);
            context.put(var.replace("$", ""), replacement);
        }
    }

    public String getCollectionValues(
            JsonSimple emailConfig, JsonSimple tfPackage, String varField) {
        String formattedCollectionValues = "";
        JSONArray subKeys = emailConfig.getArray("collections", varField, "subKeys");
        String tfPackageField = emailConfig.getString("", "collections", varField, "payloadKey");
        if (StringUtils.isNotBlank(tfPackageField) && subKeys instanceof JSONArray) {
            List<JsonObject> jsonList = new StorageDataUtil().getJavaList(tfPackage, tfPackageField);
            log.debug("Collating collection values for email template...");
            JSONArray fieldSeparators = emailConfig.getArray("collections", varField, "fieldSeparators");
            String recordSeparator = emailConfig.getString(IOUtils.LINE_SEPARATOR, "collections", varField, "recordSeparator");
            String nextDelimiter = " ";

            for (JsonObject collectionRow : jsonList) {
            	if (fieldSeparators instanceof JSONArray && fieldSeparators.size() > 0) {
            		// if no more delimiters, continue to use the last one specified
                    Object nextFieldSeparator = fieldSeparators.remove(0);
                    if (nextFieldSeparator instanceof String) {
                        nextDelimiter = (String)nextFieldSeparator;
                    }  else {
                        log.warn("Unable to retrieve String value from fieldSeparator: " + fieldSeparators.toString());
                    }
            	}
                List<String> collectionValuesList = new ArrayList<String>();
                for (Object requiredKey : subKeys) {
                	Object rawKeyValue = collectionRow.get(requiredKey);
                	if (rawKeyValue instanceof String) {
                		String keyValue = StringUtils.trim((String)rawKeyValue);
                		if (StringUtils.isNotBlank(keyValue)) {
                            collectionValuesList.add(keyValue);
                		} else if (!isVariableNameHiddenIfEmpty) {
                            collectionValuesList.add("$" + requiredKey);
                        } else {
                            log.info("blank variable name will be hidden: " + keyValue);
                        }
                	} else {
                        log.warn("No string value returned from: " + requiredKey);
                    }
                }
                formattedCollectionValues += StringUtils.join(collectionValuesList, nextDelimiter) + recordSeparator;
            }
            formattedCollectionValues = StringUtils.chomp(formattedCollectionValues, recordSeparator);
            log.debug("email formatted collection values: " + formattedCollectionValues);
        }
        return formattedCollectionValues;
    }


    /**
     * Apart from evaluating the template using velocity context, this method also adds velocity's quiet reference
     * and finally cleans up any punctuation left behind by empty references.
     *
     * @param context:   velocity context
     * @param source:  email template
     * @return : sanitized and velocity evaluated string
     */
    public String sanitizeAndEvaluateStr(String source, VelocityContext context) throws ParseErrorException, MethodInvocationException,
            ResourceNotFoundException, IOException {
        String result = preEvaluateAndSanitizeTemplate(source);
        result = evaluateStr(result, context);
        result = postEvaluateAndSanitizeTemplate(result);
        return result;
    }

    private String preEvaluateAndSanitizeTemplate(String template) {
        // we want unfilled recipients to be flagged in logging so only use default for empty variables in subject and body.
        // match only dollar signs not followed by number or already using quiet pattern
        String quietVelocityPattern = "(\\$)(?=([^\\d\\!]))";
        String quietVelocityReplacement = "$1!";
        String result = template.replaceAll(quietVelocityPattern, quietVelocityReplacement);
        result = result.replaceAll("\\$!hashString", "\\$hashString");
        return result;
    }

    private String evaluateStr(String source, VelocityContext context)
            throws ParseErrorException, MethodInvocationException,
            ResourceNotFoundException, IOException {
        StringWriter writer = new StringWriter();
        Velocity.evaluate(context, writer, "evaluateStr", source);
        return writer.toString();
    }

    private String postEvaluateAndSanitizeTemplate(String template) {
        // after empty variables removed there may be left over punctuation characters
        String result = template.replaceAll(",[ \t]*,", "");
        result = result.replaceAll("\\([ \t]*\\)", "");
        return result;
    }

    /**
     * Main processing method. This class can comprise of multiple email
     * configuration blocks, specified by 'id' array.
     * 
     */
    @Override
    public boolean process(String id, String inputKey, String outputKey,
            String stage, String configFilePath, HashMap<String, Object> dataMap)
            throws Exception {
        log.debug("Email notifier starting:" + id);
        JsonSimple config = new JsonSimple(new File(configFilePath));
        init(config);

        Indexer indexer = (Indexer) dataMap.get("indexer");

        HashSet<String> failedOids = (HashSet<String>) dataMap.get(outputKey);
        if (failedOids == null) {
            failedOids = new HashSet<String>();
        }
        Collection<String> oids = (Collection<String>) dataMap.get(inputKey);

        JSONArray emailConfigBlocks = config.getArray(id);
        if (emailConfigBlocks != null) {
            for (Object configBlockObj : emailConfigBlocks) {
                JsonSimple emailConfig = new JsonSimple(
                        (JsonObject) configBlockObj);
                doEmail(outputKey, dataMap, emailConfig, indexer, failedOids,
                        oids);
            }
        } else {
            doEmail(outputKey, dataMap, config, indexer, failedOids, oids);
        }
        dataMap.put(outputKey, failedOids);
        return true;
    }

    /**
     * Process a particular email config.
     * 
     * @param outputKey
     * @param dataMap
     * @param config
     * @param indexer
     * @param failedOids
     * @param oids
     * @throws IndexerException
     * @throws IOException
     */
    private void doEmail(String outputKey, HashMap dataMap, JsonSimple config,
            Indexer indexer, HashSet<String> failedOids, Collection<String> oids)
            throws IndexerException, IOException {
        String subjectTemplate = config.getString("", "subject");
        String bodyTemplate = config.getString("", "body");
        List<String> vars = config.getStringList("vars");
        log.debug("Email step with subject template:" + subjectTemplate);
        for (String oid : oids) {
            log.debug("Sending email notification for oid:" + oid);
            // get the solr doc
            SearchRequest searchRequest = new SearchRequest("id:" + oid);
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            indexer.search(searchRequest, result);
            SolrResult resultObject = new SolrResult(result.toString());
            List<SolrDoc> results = resultObject.getResults();
            SolrDoc solrDoc = results.get(0);
            VelocityContext context = new VelocityContext();
            initVars(solrDoc, vars, config, context);
            String subject;
            String body;
            if (isVariableNameHiddenIfEmpty) {
                subject = sanitizeAndEvaluateStr(subjectTemplate, context);
                body = sanitizeAndEvaluateStr(bodyTemplate, context);
            } else {
                subject = evaluateStr(subjectTemplate, context);
                body = evaluateStr(bodyTemplate, context);
            }
            String to = config.getString("", "to");
            String from = config.getString("", "from");
            String recipient = evaluateStr(to, context);
            if (recipient.startsWith("$")) {
                // exception encountered...
                log.error("Failed to build the email recipient:'"
                        + recipient
                        + "'. Please check the mapping field and verify that it exists and is populated in Solr.");
                failedOids.add(oid);
                continue;
            }
            if (!email(oid, from, recipient, subject, body)) {
                failedOids.add(oid);
            }
        }
    }


    /**
     * Send the actual email.
     * 
     * @param oid
     * @param from
     * @param recipient
     * @param subject
     * @param body
     * @return
     */
    public boolean email(String oid, String from, String recipient,
            String subject, String body) {
        try {
            Email email = new SimpleEmail();
            log.debug("Email host: " + host);
            log.debug("Email port: " + port);
            log.debug("Email username: " + username);
            log.debug("Email from: " + from);
            log.debug("Email to: " + recipient);
            log.debug("Email Subject is: " + subject);
            log.debug("Email Body is: " + body);
            email.setHostName(host);
            email.setSmtpPort(Integer.parseInt(port));
            email.setAuthenticator(new DefaultAuthenticator(username, password));
            // the method setSSL is deprecated on the newer versions of commons
            // email...
            email.setSSL("true".equalsIgnoreCase(ssl));
            email.setTLS("true".equalsIgnoreCase(tls));
            email.setFrom(from);
            email.setSubject(subject);
            email.setMsg(body);
            if (recipient.indexOf(",") >= 0) {
                String[] recs = recipient.split(",");
                for (String rec : recs) {
                    email.addTo(rec);
                }
            } else {
                email.addTo(recipient);
            }
            email.send();
        } catch (Exception ex) {
            log.debug("Error sending notification mail for oid:" + oid, ex);
            return false;
        }
        return true;
    }

    public boolean emailAttachment(String from, String recipient,
            String subject, String body, byte[] attachData,
            String attachDataType, String attachFileName, String attachDesc)
            throws Exception {
        MultiPartEmail email = new MultiPartEmail();
        email.attach(new ByteArrayDataSource(attachData, attachDataType),
                attachFileName, attachDesc, EmailAttachment.ATTACHMENT);
        log.debug("Email host: " + host);
        log.debug("Email port: " + port);
        log.debug("Email username: " + username);
        log.debug("Email from: " + from);
        log.debug("Email to: " + recipient);
        log.debug("Email Subject is: " + subject);
        log.debug("Email Body is: " + body);
        email.setHostName(host);
        email.setSmtpPort(Integer.parseInt(port));
        email.setAuthenticator(new DefaultAuthenticator(username, password));
        // the method setSSL is deprecated on the newer versions of commons
        // email...
        email.setSSL("true".equalsIgnoreCase(ssl));
        email.setTLS("true".equalsIgnoreCase(tls));
        email.setFrom(from);
        email.setSubject(subject);
        email.setMsg(body);
        if (recipient.indexOf(",") >= 0) {
            String[] recs = recipient.split(",");
            for (String rec : recs) {
                email.addTo(rec);
            }
        } else {
            email.addTo(recipient);
        }
        email.send();
        return true;
    }
}

