/*
 * acme4j - Java ACME client
 *
 * Copyright (C) 2017 Richard "Shred" Körber
 *   http://acme4j.shredzone.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.shredzone.acme4j;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.shredzone.acme4j.toolbox.AcmeUtils.parseTimestamp;
import static org.shredzone.acme4j.toolbox.TestUtils.getJSON;
import static org.shredzone.acme4j.toolbox.TestUtils.url;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.shredzone.acme4j.connector.Resource;
import org.shredzone.acme4j.exception.AcmeNotSupportedException;
import org.shredzone.acme4j.provider.TestableConnectionProvider;
import org.shredzone.acme4j.toolbox.JSON;
import org.shredzone.acme4j.toolbox.JSONBuilder;
import org.shredzone.acme4j.toolbox.TestUtils;

/**
 * Unit tests for {@link OrderBuilder}.
 */
public class OrderBuilderTest {

    private final URL resourceUrl  = url("http://example.com/acme/resource");
    private final URL locationUrl  = url(TestUtils.ACCOUNT_URL);

    /**
     * Test that a new {@link Order} can be created.
     */
    @Test
    public void testOrderCertificate() throws Exception {
        var notBefore = parseTimestamp("2016-01-01T00:00:00Z");
        var notAfter = parseTimestamp("2016-01-08T00:00:00Z");

        var provider = new TestableConnectionProvider() {
            @Override
            public int sendSignedRequest(URL url, JSONBuilder claims, Login login) {
                assertThat(url).isEqualTo(resourceUrl);
                assertThatJson(claims.toString()).isEqualTo(getJSON("requestOrderRequest").toString());
                assertThat(login).isNotNull();
                return HttpURLConnection.HTTP_CREATED;
            }

            @Override
            public JSON readJsonResponse() {
                return getJSON("requestOrderResponse");
            }

            @Override
            public Optional<URL> getLocation() {
                return Optional.of(locationUrl);
            }
        };

        var login = provider.createLogin();

        provider.putTestResource(Resource.NEW_ORDER, resourceUrl);

        var account = new Account(login);
        var order = account.newOrder()
                        .domains("example.com", "www.example.com")
                        .domain("example.org")
                        .domains(Arrays.asList("m.example.com", "m.example.org"))
                        .identifier(Identifier.dns("d.example.com"))
                        .identifiers(Arrays.asList(
                                    Identifier.dns("d2.example.com"),
                                    Identifier.ip(InetAddress.getByName("192.168.1.2"))))
                        .notBefore(notBefore)
                        .notAfter(notAfter)
                        .create();

        try (var softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(order.getIdentifiers()).containsExactlyInAnyOrder(
                        Identifier.dns("example.com"),
                        Identifier.dns("www.example.com"),
                        Identifier.dns("example.org"),
                        Identifier.dns("m.example.com"),
                        Identifier.dns("m.example.org"),
                        Identifier.dns("d.example.com"),
                        Identifier.dns("d2.example.com"),
                        Identifier.ip(InetAddress.getByName("192.168.1.2")));
            softly.assertThat(order.getNotBefore().orElseThrow())
                    .isEqualTo("2016-01-01T00:10:00Z");
            softly.assertThat(order.getNotAfter().orElseThrow())
                    .isEqualTo("2016-01-08T00:10:00Z");
            softly.assertThat(order.getExpires().orElseThrow())
                    .isEqualTo("2016-01-10T00:00:00Z");
            softly.assertThat(order.getStatus()).isEqualTo(Status.PENDING);
            softly.assertThat(order.isAutoRenewing()).isFalse();
            softly.assertThatExceptionOfType(AcmeNotSupportedException.class)
                    .isThrownBy(order::getAutoRenewalStartDate);
            softly.assertThatExceptionOfType(AcmeNotSupportedException.class)
                    .isThrownBy(order::getAutoRenewalEndDate);
            softly.assertThatExceptionOfType(AcmeNotSupportedException.class)
                    .isThrownBy(order::getAutoRenewalLifetime);
            softly.assertThatExceptionOfType(AcmeNotSupportedException.class)
                    .isThrownBy(order::getAutoRenewalLifetimeAdjust);
            softly.assertThatExceptionOfType(AcmeNotSupportedException.class)
                    .isThrownBy(order::isAutoRenewalGetEnabled);
            softly.assertThat(order.getLocation()).isEqualTo(locationUrl);
            softly.assertThat(order.getAuthorizations()).isNotNull();
            softly.assertThat(order.getAuthorizations()).hasSize(2);
        }

        provider.close();
    }

    /**
     * Test that a new auto-renewal {@link Order} can be created.
     */
    @Test
    public void testAutoRenewOrderCertificate() throws Exception {
        var autoRenewStart = parseTimestamp("2018-01-01T00:00:00Z");
        var autoRenewEnd = parseTimestamp("2019-01-01T00:00:00Z");
        var validity = Duration.ofDays(7);
        var predate = Duration.ofDays(6);

        var provider = new TestableConnectionProvider() {
            @Override
            public int sendSignedRequest(URL url, JSONBuilder claims, Login login) {
                assertThat(url).isEqualTo(resourceUrl);
                assertThatJson(claims.toString()).isEqualTo(getJSON("requestAutoRenewOrderRequest").toString());
                assertThat(login).isNotNull();
                return HttpURLConnection.HTTP_CREATED;
            }

            @Override
            public JSON readJsonResponse() {
                return getJSON("requestAutoRenewOrderResponse");
            }

            @Override
            public Optional<URL> getLocation() {
                return Optional.of(locationUrl);
            }
        };

        var login = provider.createLogin();

        provider.putMetadata("auto-renewal", JSON.empty());
        provider.putTestResource(Resource.NEW_ORDER, resourceUrl);

        var account = new Account(login);
        var order = account.newOrder()
                        .domain("example.org")
                        .autoRenewal()
                        .autoRenewalStart(autoRenewStart)
                        .autoRenewalEnd(autoRenewEnd)
                        .autoRenewalLifetime(validity)
                        .autoRenewalLifetimeAdjust(predate)
                        .autoRenewalEnableGet()
                        .create();

        try (var softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(order.getIdentifiers()).containsExactlyInAnyOrder(Identifier.dns("example.org"));
            softly.assertThat(order.getNotBefore()).isEmpty();
            softly.assertThat(order.getNotAfter()).isEmpty();
            softly.assertThat(order.isAutoRenewing()).isTrue();
            softly.assertThat(order.getAutoRenewalStartDate().orElseThrow()).isEqualTo(autoRenewStart);
            softly.assertThat(order.getAutoRenewalEndDate()).isEqualTo(autoRenewEnd);
            softly.assertThat(order.getAutoRenewalLifetime()).isEqualTo(validity);
            softly.assertThat(order.getAutoRenewalLifetimeAdjust().orElseThrow()).isEqualTo(predate);
            softly.assertThat(order.isAutoRenewalGetEnabled()).isTrue();
            softly.assertThat(order.getLocation()).isEqualTo(locationUrl);
        }

        provider.close();
    }

    /**
     * Test that an auto-renewal {@link Order} cannot be created if unsupported by the CA.
     */
    @Test
    public void testAutoRenewOrderCertificateFails() {
        assertThrows(AcmeNotSupportedException.class, () -> {
            var provider = new TestableConnectionProvider();
            provider.putTestResource(Resource.NEW_ORDER, resourceUrl);

            var login = provider.createLogin();

            var account = new Account(login);
            account.newOrder()
                            .domain("example.org")
                            .autoRenewal()
                            .create();

            provider.close();
        });
    }

    /**
     * Test that auto-renew and notBefore/notAfter cannot be mixed.
     */
    @Test
    public void testAutoRenewNotMixed() throws Exception {
        var someInstant = parseTimestamp("2018-01-01T00:00:00Z");

        var provider = new TestableConnectionProvider();
        var login = provider.createLogin();

        var account = new Account(login);

        assertThrows(IllegalArgumentException.class, () -> {
            OrderBuilder ob = account.newOrder().autoRenewal();
            ob.notBefore(someInstant);
        }, "accepted notBefore");

        assertThrows(IllegalArgumentException.class, () -> {
            OrderBuilder ob = account.newOrder().autoRenewal();
            ob.notAfter(someInstant);
        }, "accepted notAfter");

        assertThrows(IllegalArgumentException.class, () -> {
            OrderBuilder ob = account.newOrder().notBefore(someInstant);
            ob.autoRenewal();
        }, "accepted autoRenewal");

        assertThrows(IllegalArgumentException.class, () -> {
            OrderBuilder ob = account.newOrder().notBefore(someInstant);
            ob.autoRenewalStart(someInstant);
        }, "accepted autoRenewalStart");

        assertThrows(IllegalArgumentException.class, () -> {
            OrderBuilder ob = account.newOrder().notBefore(someInstant);
            ob.autoRenewalEnd(someInstant);
        }, "accepted autoRenewalEnd");

        assertThrows(IllegalArgumentException.class, () -> {
            OrderBuilder ob = account.newOrder().notBefore(someInstant);
            ob.autoRenewalLifetime(Duration.ofDays(7));
        }, "accepted autoRenewalLifetime");

        provider.close();
    }

}
