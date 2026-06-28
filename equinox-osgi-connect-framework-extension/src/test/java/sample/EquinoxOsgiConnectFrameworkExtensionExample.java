package sample;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.osgi.internal.framework.EquinoxConfiguration;
import org.eclipse.osgi.service.resolver.PlatformAdmin;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.connect.ConnectContent;
import org.osgi.framework.connect.ConnectContent.ConnectEntry;
import org.osgi.framework.connect.ConnectFrameworkFactory;
import org.osgi.framework.connect.ConnectModule;
import org.osgi.framework.connect.ModuleConnector;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;

public class EquinoxOsgiConnectFrameworkExtensionExample {

	static URI x(String bundle) {
		for (String p : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))) {
			Path path = Path.of(p);
			if (path.getFileName().toString().endsWith(".jar")
					&& path.getFileName().toString().startsWith(bundle + "-"))
				try {
					return new URI("jar:" + path.toUri().toURL() + "!/");
				} catch (URISyntaxException | MalformedURLException e) {
					throw new RuntimeException(e);
				}
		}
		System.err.println("not found: " + bundle);
		return null;
	}

	public static class SampleConnectEntry implements ConnectEntry {

		SampleConnectEntry(URL url) {
			this.url = url;
		}

		private final URL url;

		@Override
		public String getName() {
			System.err.println("not implemented: getName");
			return null;
		}

		@Override
		public long getContentLength() {
			System.err.println("not implemented: getContentLength");
			return 0;
		}

		@Override
		public long getLastModified() {
			System.err.println("not implemented: getLastModified");
			return 0;
		}

		@Override
		public InputStream getInputStream() throws IOException {
			return url.openStream();
		}

	}

	public static class SampleConnectContent implements ConnectContent {

		final URI url;

		SampleConnectContent(URI url) {
			this.url = url;
		}

		@Override
		public Optional<Map<String, String>> getHeaders() {
			try {
				try (InputStream openStream = URI.create(url + "META-INF/MANIFEST.MF").toURL().openStream()) {
					Manifest mf = new Manifest();
					mf.read(openStream);
					return Optional.of(mf.getMainAttributes().entrySet().stream()
							.collect(Collectors.toMap(x -> x.getKey().toString(), x -> x.getValue().toString())));
				}
			} catch (IOException e) {
				e.printStackTrace();
				throw new UncheckedIOException(e);
			}
		}

		@Override
		public Iterable<String> getEntries() throws IOException {
			System.err.println("not implemented: getEntries");
			return null;
		}

		@Override
		public Optional<ConnectEntry> getEntry(String path) {
			try {
				URL entryUrl = URI.create(url + path).toURL();
				try (var _ = entryUrl.openStream()) {
				} catch (FileNotFoundException e) {
					System.err.println("not found: " + entryUrl);
					return Optional.empty();
				}
				return Optional.of(new SampleConnectEntry(entryUrl));
			} catch (IOException e1) {
				e1.printStackTrace();
				return Optional.empty();
			}
		}

		@Override
		public Optional<ClassLoader> getClassLoader() {
			return Optional.of(EquinoxOsgiConnectFrameworkExtensionExample.class.getClassLoader());
		}

		@Override
		public void open() throws IOException {
		}

		@Override
		public void close() throws IOException {
		}

	}

	public static class SampleConnectModule implements ConnectModule {

		final URI url;

		SampleConnectModule(URI url) {
			this.url = url;
		}

		@Override
		public ConnectContent getContent() throws IOException {
			return new SampleConnectContent(url);
		}

	}

	public static class SampleModuleConnector implements ModuleConnector {

		@Override
		public void initialize(File storage, Map<String, String> configuration) {
		}

		@Override
		public Optional<ConnectModule> connect(String location) throws BundleException {
			URI uri;
			if (location.equals(Constants.SYSTEM_BUNDLE_LOCATION)) {
				uri = x("org.eclipse.osgi");
			} else {
				uri = x(location);
			}
			return Optional.ofNullable(uri).map(SampleConnectModule::new);
		}

		@Override
		public Optional<BundleActivator> newBundleActivator() {
			return Optional.empty();
		}

	}

	@Test
	public void test() throws Exception {

		Path tempDirectory = Files.createTempDirectory("osgi-connect-example");

		Map<String, String> frameworkConfiguration = new HashMap<>();

		frameworkConfiguration.put(Constants.FRAMEWORK_STORAGE, tempDirectory.toString());
		frameworkConfiguration.put(Constants.FRAMEWORK_STORAGE_CLEAN, "true");

		String bootdelegationProperty = EquinoxConfiguration.PROP_COMPATIBILITY_BOOTDELEGATION;
		frameworkConfiguration.put(bootdelegationProperty, "true");

		ConnectFrameworkFactory cff = ServiceLoader.load(ConnectFrameworkFactory.class).iterator().next();

		SampleModuleConnector mmc = new SampleModuleConnector();
		Framework framework = cff.newFramework(frameworkConfiguration, mmc);

		framework.start();

		framework.getBundleContext().addFrameworkListener(event -> {
			System.err.println("FRAMEWORK EVENT " + event + " " + event.getType());
			if (event.getThrowable() != null)
				event.getThrowable().printStackTrace(System.err);
		});

		String[] deps = new String[] { "org.osgi.util.function", "org.osgi.util.promise", "org.osgi.service.component",
				"org.apache.felix.scr", "org.eclipse.osgi.compatibility.state", "org.eclipse.equinox.common" };

		for (String dep : deps) {
			Bundle bundle = framework.getBundleContext().installBundle(dep);
			System.err.println("installed: " + bundle);

			if (dep.equals("org.apache.felix.scr") || dep.equals("org.eclipse.equinox.common"))
				bundle.adapt(BundleStartLevel.class).setStartLevel(2);
			else
				bundle.adapt(BundleStartLevel.class).setStartLevel(4);
		}

		// set start level
		{
			FrameworkStartLevel fsl = framework.getBundleContext().getBundle(0).adapt(FrameworkStartLevel.class);
			CountDownLatch cdl = new CountDownLatch(1);

			fsl.setStartLevel(4, event -> {
				if (event.getType() == FrameworkEvent.STARTLEVEL_CHANGED)
					cdl.countDown();
			});

			try {
				cdl.await();
			} catch (InterruptedException e1) {
				Thread.currentThread().interrupt();
				throw new RuntimeException(e1);
			}
		}

		ServiceReference<PlatformAdmin> sr = framework.getBundleContext().getServiceReference(PlatformAdmin.class);
		System.err.println("SR " + sr);

		// hack: execute activator manually
		new org.eclipse.osgi.compatibility.state.Activator().start(framework.getBundleContext());

		sr = framework.getBundleContext().getServiceReference(PlatformAdmin.class);
		System.err.println("SR " + sr);
	}

}
