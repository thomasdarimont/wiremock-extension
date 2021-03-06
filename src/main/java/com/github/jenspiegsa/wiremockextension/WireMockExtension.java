package com.github.jenspiegsa.wiremockextension;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static org.junit.platform.commons.util.ReflectionUtils.makeAccessible;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.util.AnnotationUtils;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.VerificationException;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.Options;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.github.tomakehurst.wiremock.verification.NearMiss;

/**
 * @author Jens Piegsa
 */
public class WireMockExtension implements BeforeEachCallback, AfterEachCallback, TestInstancePostProcessor {

	private boolean generalFailOnUnmatchedRequests;
	private final Map<String, List<WireMockServer>> serversByTestId = new LinkedHashMap<>();

	// This constructor is invoked by JUnit via reflection
	@SuppressWarnings("unused")
	private WireMockExtension() {
		this(true);
	}

	public WireMockExtension(final boolean failOnUnmatchedRequests) {
		this.generalFailOnUnmatchedRequests = failOnUnmatchedRequests;
	}

	@Override
	public void postProcessTestInstance(final Object testInstance, final ExtensionContext context) throws Exception {

		final List<WireMockServer> servers = new ArrayList<>();
		for (final Field field : retrieveAnnotatedFields(context, Managed.class, WireMockServer.class)) {
			servers.add((WireMockServer) requireNonNull(makeAccessible(field).get(testInstance)));
		}

		if (servers.isEmpty()) {
			Options options = null;
			for (final Field field : retrieveAnnotatedFields(context, ConfigureWireMock.class, Options.class)) {
				if (options == null) {
					options = (Options) makeAccessible(field).get(testInstance);
				} else {
					throw new ExtensionConfigurationException("@ConfigureWireMock only valid once per class.");
				}
			}
			if (options == null) {
				options = wireMockConfig();
			}

			WireMockServer server = null;
			for (final Field field : retrieveAnnotatedFields(context, InjectServer.class, WireMockServer.class)) {
				if (server == null) {
					server = new WireMockServer(options);
					servers.add(server);
				}
				makeAccessible(field).set(testInstance, server);
			}
		}

		serversByTestId.computeIfAbsent(context.getUniqueId(), k -> new ArrayList<>())
				.addAll(servers);

		servers.forEach(WireMockExtension::startServer);
	}

	@Override
	public void beforeEach(final ExtensionContext context) {

		final Optional<WireMockSettings> wireMockSettings = retrieveAnnotation(context, WireMockSettings.class);
		generalFailOnUnmatchedRequests = wireMockSettings
				.map(WireMockSettings::failOnUnmatchedRequests)
				.orElse(generalFailOnUnmatchedRequests);

		if (isSimpleCase(context)) {
			final WireMockServer server = new WireMockServer();
			serversByTestId.put(context.getUniqueId(), singletonList(server));
			startServer(server);
		}
	}

	/** @returns {@code true}, if there is no custom annotation / configuration present. */
	private boolean isSimpleCase(final ExtensionContext context) {
		boolean isSimpleCase = true;
		ExtensionContext currentContext = context;
		while (currentContext != null) {
			isSimpleCase &= serversByTestId.getOrDefault(currentContext.getUniqueId(), emptyList()).isEmpty();
			currentContext = currentContext.getParent().orElse(null);
		}
		return isSimpleCase;
	}

	@Override
	public void afterEach(final ExtensionContext context) {

		ExtensionContext currentContext = context;
		while (currentContext != null) {

			final List<WireMockServer> servers = serversByTestId.get(currentContext.getUniqueId());
			if (servers != null) {
				servers.forEach(WireMockExtension::stopServer);
			}

			checkForUnmatchedRequests(currentContext);
			currentContext = currentContext.getParent().orElse(null);
		}
	}

	private void checkForUnmatchedRequests(final ExtensionContext context) {
		final List<WireMockServer> servers = serversByTestId.get(context.getUniqueId());
		if (servers != null) {
			servers.forEach(server -> {
				final boolean mustCheck = Optional.of(server)
						.filter(ManagedWireMockServer.class::isInstance)
						.map(ManagedWireMockServer.class::cast)
						.map(ManagedWireMockServer::failOnUnmatchedRequests)
						.orElse(generalFailOnUnmatchedRequests);

				if (mustCheck) {
					final List<LoggedRequest> unmatchedRequests = server.findAllUnmatchedRequests();
					if (!unmatchedRequests.isEmpty()) {
						final List<NearMiss> nearMisses = server.findNearMissesForAllUnmatchedRequests();
						throw nearMisses.isEmpty()
								? VerificationException.forUnmatchedRequests(unmatchedRequests)
								: VerificationException.forUnmatchedNearMisses(nearMisses);
					}
				}
			});
		}
	}

	private static <A extends Annotation> Optional<A> retrieveAnnotation(final ExtensionContext context,
	                                                                     final Class<A> annotationType) {

		Optional<ExtensionContext> currentContext = Optional.of(context);
		Optional<A> annotation = Optional.empty();

		while (currentContext.isPresent() && !annotation.isPresent()) {
			annotation = AnnotationSupport.findAnnotation(currentContext.get().getElement(), annotationType);
			currentContext = currentContext.get().getParent();
		}
		return annotation;
	}

	private static List<Field> retrieveAnnotatedFields(final ExtensionContext context,
	                                                   final Class<? extends Annotation> annotationType,
	                                                   final Class<?> fieldType) {

		return context.getElement()
			.filter(Class.class::isInstance)
			.map(Class.class::cast)
			.map(testInstanceClass ->
				AnnotationUtils.findAnnotatedFields(testInstanceClass, annotationType, field -> fieldType.isAssignableFrom(field.getType()))
			)
			.orElseGet(Collections::emptyList);
	}

	private static void startServer(final WireMockServer server) {
		if (!server.isRunning()) {
			server.start();
			WireMock.configureFor("localhost", server.port());
		}
	}

	private static void stopServer(final WireMockServer server) {
		server.stop();
	}
}
