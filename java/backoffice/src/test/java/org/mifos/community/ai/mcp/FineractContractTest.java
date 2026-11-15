/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.mifos.community.ai.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mifos.community.ai.mcp.client.MifosXClient;

/**
 * Validates the payloads the Java MCP write tools send against contract/fineract_write_contract.json.
 *
 * Asserts against the field rules Fineract's own validators allow and require for each operation,
 * and ensures tenant-specific IDs are never hardcoded defaults.
 */
public class FineractContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode contractRoot;
    private static JsonNode operationsNode;

    @BeforeAll
    public static void setUp() throws Exception {
        try (InputStream is = FineractContractTest.class.getResourceAsStream("/contract/fineract_write_contract.json")) {
            assertNotNull(is, "fineract_write_contract.json must be present in classpath");
            contractRoot = MAPPER.readTree(is);
            operationsNode = contractRoot.get("operations");
            assertNotNull(operationsNode, "operations field must be present in contract");
        }
    }

    @Test
    @DisplayName("Verify write contract JSON loads and contains required operations")
    public void testContractLoads() {
        assertTrue(operationsNode.has("charge.create"));
        assertTrue(operationsNode.has("loan.repayment"));
        assertTrue(operationsNode.has("savings.deposit"));
        assertTrue(operationsNode.has("savings.withdrawal"));
    }

    @Test
    @DisplayName("loan.repayment: makeLoanRepayment payload conforms to Fineract contract")
    public void testMakeLoanRepaymentContract() throws Exception {
        CapturedCall captured = new CapturedCall();

        // Stub MifosXClient template response
        ObjectNode templateNode = MAPPER.createObjectNode();
        templateNode.put("date", "10 June 2025");
        templateNode.put("amount", 100.0);
        ArrayNode options = templateNode.putArray("paymentTypeOptions");
        ObjectNode cashOption = options.addObject();
        cashOption.put("id", 4);
        cashOption.put("name", "Money Transfer");

        MifosXClient mockClient = createMockClient(templateNode, captured);

        MifosXServer server = new MifosXServer();
        server.mifosXClient = mockClient;

        server.makeLoanRepayment(
                1,                  // loanAccountNumber
                "Money Transfer",   // paymentType
                100.0,              // amount
                "10 June 2025",     // transactionDate
                "EXT-001",          // externalId
                "Test Note",        // note
                null, null, null, null, null
        );

        assertNotNull(captured.payload, "Payload must be captured");
        JsonNode payloadJson = MAPPER.readTree(captured.payload);

        Map<String, Object> chosenTenantIds = new HashMap<>();
        chosenTenantIds.put("paymentTypeId", 4);

        List<String> violations = checkContractViolations(
                "loan.repayment",
                "loans/1/transactions?command=repayment",
                payloadJson,
                chosenTenantIds
        );

        assertTrue(violations.isEmpty(), "Violations found: " + violations);
    }

    @Test
    @DisplayName("charge.create: createCharge payload conforms to Fineract contract")
    public void testCreateChargeSavingsContract() throws Exception {
        CapturedCall captured = new CapturedCall();
        ObjectNode emptyResponse = MAPPER.createObjectNode();
        MifosXClient mockClient = createMockClient(emptyResponse, captured);

        MifosXServer server = new MifosXServer();
        server.mifosXClient = mockClient;

        server.createCharge(
                "Withdrawal fee",   // name
                "2.0",              // amount
                "USD",              // currencyCode
                2,                  // chargeAppliesTo (Savings = 2)
                5,                  // chargeTimeType (Withdrawal fee = 5)
                1,                  // chargeCalculationType (Flat = 1)
                "false",            // penalty
                "true"              // active
        );

        assertNotNull(captured.payload, "Payload must be captured");
        JsonNode payloadJson = MAPPER.readTree(captured.payload);

        List<String> violations = checkContractViolations(
                "charge.create",
                "charges",
                payloadJson,
                Collections.emptyMap()
        );

        assertTrue(violations.isEmpty(), "Violations found: " + violations);
    }

    @Test
    @DisplayName("savings.deposit: newSavingsTransaction deposit conforms to Fineract contract")
    public void testSavingsDepositContract() throws Exception {
        CapturedCall captured = new CapturedCall();

        ObjectNode templateNode = MAPPER.createObjectNode();
        templateNode.put("date", "10 June 2025");
        ArrayNode options = templateNode.putArray("paymentTypeOptions");
        ObjectNode transfer = options.addObject();
        transfer.put("id", 4);
        transfer.put("name", "Money Transfer");

        MifosXClient mockClient = createMockClient(templateNode, captured);

        MifosXServer server = new MifosXServer();
        server.mifosXClient = mockClient;

        server.newSavingsTransaction(
                8,                  // accountId
                "deposit",          // transaction
                "Deposit note",     // note
                "Money Transfer",   // paymentType
                50.0,               // transactionAmount
                "10 June 2025"      // transactionDate
        );

        assertNotNull(captured.payload, "Payload must be captured");
        JsonNode payloadJson = MAPPER.readTree(captured.payload);

        Map<String, Object> chosenTenantIds = new HashMap<>();
        chosenTenantIds.put("paymentTypeId", 4);

        List<String> violations = checkContractViolations(
                "savings.deposit",
                "savingsaccounts/8/transactions?command=deposit",
                payloadJson,
                chosenTenantIds
        );

        assertTrue(violations.isEmpty(), "Violations found: " + violations);
    }

    @Test
    @DisplayName("savings.withdrawal: newSavingsTransaction withdrawal conforms to Fineract contract")
    public void testSavingsWithdrawalContract() throws Exception {
        CapturedCall captured = new CapturedCall();

        ObjectNode templateNode = MAPPER.createObjectNode();
        templateNode.put("date", "10 June 2025");
        ArrayNode options = templateNode.putArray("paymentTypeOptions");
        ObjectNode transfer = options.addObject();
        transfer.put("id", 4);
        transfer.put("name", "Money Transfer");

        MifosXClient mockClient = createMockClient(templateNode, captured);

        MifosXServer server = new MifosXServer();
        server.mifosXClient = mockClient;

        server.newSavingsTransaction(
                8,                  // accountId
                "withdrawal",       // transaction
                "Withdrawal note",  // note
                "Money Transfer",   // paymentType
                20.0,               // transactionAmount
                "10 June 2025"      // transactionDate
        );

        assertNotNull(captured.payload, "Payload must be captured");
        JsonNode payloadJson = MAPPER.readTree(captured.payload);

        Map<String, Object> chosenTenantIds = new HashMap<>();
        chosenTenantIds.put("paymentTypeId", 4);

        List<String> violations = checkContractViolations(
                "savings.withdrawal",
                "savingsaccounts/8/transactions?command=withdrawal",
                payloadJson,
                chosenTenantIds
        );

        assertTrue(violations.isEmpty(), "Violations found: " + violations);
    }

    // --- Contract Validation Helpers ---

    private static List<String> checkContractViolations(
            String operationName,
            String endpoint,
            JsonNode payload,
            Map<String, Object> chosenTenantIds
    ) {
        List<String> violations = new ArrayList<>();
        JsonNode op = operationsNode.get(operationName);
        if (op == null) {
            violations.add("Unknown operation: " + operationName);
            return violations;
        }

        // 1. Endpoint match
        String expectedPath = op.path("path").asText();
        String expectedCommand = op.has("command") ? "?command=" + op.get("command").asText() : "";
        String regex = "^" + expectedPath.replace("{id}", "\\d+") + Pattern.quote(expectedCommand) + "$";
        if (!Pattern.compile(regex).matcher(endpoint).matches()) {
            violations.add("Endpoint mismatch: posted to " + endpoint + ", expected " + expectedPath + expectedCommand);
        }

        // 2. Allowed parameters
        Set<String> allowed = new HashSet<>();
        for (JsonNode field : op.path("allowed")) {
            allowed.add(field.asText());
        }
        Iterator<String> fieldNames = payload.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!allowed.contains(field)) {
                violations.add("Field not allowed by Fineract: " + field);
            }
        }

        // 3. Required parameters
        for (JsonNode req : op.path("required")) {
            String reqField = req.asText();
            if (!payload.hasNonNull(reqField)) {
                violations.add("Missing required field: " + reqField);
            }
        }

        // 4. Tenant data checks (must only send what user chose)
        for (JsonNode tenantIdNode : op.path("tenant_ids")) {
            String field = tenantIdNode.asText();
            if (payload.has(field) && !chosenTenantIds.containsKey(field)) {
                violations.add("Tenant ID field '" + field + "' sent without user choice: " + payload.get(field));
            }
        }

        // 5. Conditional rules (required_when)
        if (op.has("required_when")) {
            for (JsonNode rule : op.get("required_when")) {
                if (matchesCondition(rule.get("if"), payload)) {
                    for (JsonNode thenField : rule.get("then")) {
                        if (!payload.hasNonNull(thenField.asText())) {
                            violations.add("Conditional required field missing: " + thenField.asText());
                        }
                    }
                }
            }
        }

        // 6. One of when
        if (op.has("one_of_when")) {
            for (JsonNode rule : op.get("one_of_when")) {
                if (matchesCondition(rule.get("if"), payload)) {
                    String field = rule.get("field").asText();
                    if (payload.has(field)) {
                        int val = payload.get(field).asInt();
                        boolean found = false;
                        for (JsonNode allowedVal : rule.get("in")) {
                            if (allowedVal.asInt() == val) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            violations.add("Field '" + field + "' value " + val + " not in allowed set " + rule.get("in"));
                        }
                    }
                }
            }
        }

        return violations;
    }

    private static boolean matchesCondition(JsonNode condition, JsonNode payload) {
        if (condition == null) return false;
        Iterator<Map.Entry<String, JsonNode>> fields = condition.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if (!payload.has(key)) return false;
            if (!payload.get(key).asText().equals(entry.getValue().asText())) return false;
        }
        return true;
    }

    private static class CapturedCall {
        String method;
        String path;
        String payload;
    }

    private static MifosXClient createMockClient(JsonNode templateResponse, CapturedCall captured) {
        return (MifosXClient) Proxy.newProxyInstance(
                MifosXClient.class.getClassLoader(),
                new Class<?>[]{MifosXClient.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("getLoanRepaymentTemplate".equals(methodName) || "getSavingsTransactionTemplate".equals(methodName)) {
                        return templateResponse;
                    }
                    if ("loanRepayment".equals(methodName)) {
                        captured.method = "POST";
                        captured.payload = (String) args[2];
                        return MAPPER.createObjectNode().put("resourceId", 1);
                    }
                    if ("createCharge".equals(methodName)) {
                        captured.method = "POST";
                        captured.payload = (String) args[0];
                        return MAPPER.createObjectNode().put("resourceId", 10);
                    }
                    if ("newSavingsTransaction".equals(methodName)) {
                        captured.method = "POST";
                        captured.payload = (String) args[2];
                        return MAPPER.createObjectNode().put("resourceId", 15);
                    }
                    return MAPPER.createObjectNode();
                }
        );
    }
}
