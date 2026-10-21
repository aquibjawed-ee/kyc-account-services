package com.example.kyc.creation.support;

import com.example.kyc.creation.validation.Verhoeff;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.ThreadLocalRandom;

public final class Fixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixtures() {}

    public static String randomPan() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append((char) ('A' + r.nextInt(26)));
        for (int i = 0; i < 4; i++) sb.append(r.nextInt(10));
        return sb.append((char) ('A' + r.nextInt(26))).toString();
    }

    /** Checksum-valid Aadhaar: first digit 2-9, 10 random digits, Verhoeff check digit. */
    public static String validAadhaar() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder().append(2 + r.nextInt(8));
        for (int i = 0; i < 10; i++) sb.append(r.nextInt(10));
        return sb.append(Verhoeff.checkDigit(sb.toString())).toString();
    }

    public static ObjectNode person(String pan) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("name", "Asha Rao");
        p.put("address", "12 MG Road, Bengaluru");
        p.put("pan", pan);
        p.put("aadhaar", validAadhaar());
        p.put("parentsName", "Ravi Rao");
        p.put("annualIncome", 1250000.50);
        p.put("incomeSource", "Salary");
        return p;
    }

    public static ObjectNode createBody() {
        return createBody(randomPan());
    }

    public static ObjectNode createBody(String primaryPan) {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("primary", person(primaryPan));
        body.set("nominee", person(randomPan()));
        body.set("guarantor", person(randomPan()));
        return body;
    }
}
