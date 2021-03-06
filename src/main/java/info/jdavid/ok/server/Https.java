package info.jdavid.ok.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import static info.jdavid.ok.server.Logger.logger;


/**
 * Https is used to access the server certificates required by the server for HTTPS.
 * Certificates should be in pkcs12 format.<br>
 * If necessary, you can convert an openssl certificate with: <br>
 *   <code>openssl pkcs12 -export -in path_to_certificate.crt -inkey path_to_key.key -out path_for_generated.p12 -passout pass:</code>
 */
@SuppressWarnings("WeakerAccess")
public final class Https {

  final SSLContext context;
  final Map<String, SSLContext> additionalContexts;
  final Platform platform;
  final String[] protocols;
  final String[] cipherSuites;
  final boolean http2;

  private Https(@Nullable final byte[] cert,
                @Nullable final Map<String, byte[]> additionalCerts,
                @Nullable final List<String> protocols,
                @Nullable final List<String> cipherSuites,
                final boolean http2) {
    context = createSSLContext(cert);
    final Platform platform = this.platform = Platform.findPlatform();
    additionalContexts =
      new HashMap<>(additionalCerts == null ? 0 : additionalCerts.size());
    if (additionalCerts != null) {
      for (final Map.Entry<String, byte[]> entry : additionalCerts.entrySet()) {
        final SSLContext additionalContext = createSSLContext(entry.getValue());
        if (additionalContext != null) additionalContexts.put(entry.getKey(), additionalContext);
      }
    }
    final List<String> protos = protocols == null ? platform.defaultProtocols() : protocols;
    this.protocols = protos.toArray(new String[protos.size()]);
    final List<String> ciphers = cipherSuites == null ? platform.defaultCipherSuites() : cipherSuites;
    this.cipherSuites = ciphers.toArray(new String[ciphers.size()]);
    this.http2 = http2 && platform.supportsHttp2();
  }

  SSLContext getContext(@Nullable final String host) {
    if (host == null) return context;
    final SSLContext additionalContext = additionalContexts.get(host);
    return additionalContext == null ? context : additionalContext;
  }

  SSLSocket createSSLSocket(final Socket socket,
                            @Nullable final String hostname, final boolean http2) throws IOException {
    final SSLSocketFactory sslFactory = getContext(hostname).getSocketFactory();
    final SSLSocket sslSocket = (SSLSocket)sslFactory.createSocket(socket, null, socket.getPort(), true);
    platform.setupSSLSocket(sslSocket, http2);
    sslSocket.setUseClientMode(false);
    sslSocket.setEnabledProtocols(protocols);
    sslSocket.setEnabledCipherSuites(cipherSuites);
    sslSocket.startHandshake();
    return sslSocket;
  }

  private static SSLContext createSSLContext(@Nullable final byte[] certificate) {
    if (certificate == null) return null;
    final InputStream cert = new ByteArrayInputStream(certificate);
    try {
      final KeyStore keyStore = KeyStore.getInstance("PKCS12");
      keyStore.load(cert, new char[0]);
      final KeyManagerFactory kmf =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keyStore, new char[0]);
      final KeyStore trustStore = KeyStore.getInstance("JKS");
      trustStore.load(null, null);
      final TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(trustStore);
      final SSLContext context = SSLContext.getInstance("TLS");
      context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
      return context;
    }
    catch (final GeneralSecurityException e) {
      logger.warn("Failed to create SSL context.", e);
      return null;
    }
    catch (final IOException e) {
      logger.warn("Failed to load SSL certificate.", e);
      return null;
    }
    finally {
      try {
        cert.close();
      }
      catch (final IOException ignore) {}
    }
  }

  @SuppressWarnings({ "WeakerAccess", "unused" })
  public enum Protocol {
    SSL_3("SSLv3"), TLS_1("TLSv1"), TLS_1_1("TLSv1.1"), TLS_1_2("TLSv1.2");

    final String name;

    Protocol(final String name) {
      this.name = name;
    }

  }

  /**
   * Builder for the Https class.
   */
  @SuppressWarnings("unused")
  public static final class Builder {

    private boolean mHttp2 = false;
    private List<String> mProtocols = null;
    private List<String> mCipherSuites = null;
    private byte[] mCertificate = null;
    private final Map<String, byte[]> mAdditionalCertificates = new HashMap<>(4);

    public Builder() {}

    /**
     * Sets the primary certificate.
     * @param bytes the certificate (pkcs12).
     * @return this.
     */
    public Builder certificate(final byte[] bytes) {
      return certificate(bytes, true);
    }

    /**
     * Sets the primary certificate.
     * @param bytes the certificate (pkcs12).
     * @param allowHttp2 whether to enable http2 (the platform needs to support it).
     * @return this.
     */
    public Builder certificate(final byte[] bytes, final boolean allowHttp2) {
      if (mCertificate != null) throw new IllegalStateException("Main certificate already set.");
      mCertificate = bytes;
      mHttp2 = allowHttp2;
      return this;
    }

    /**
     * Adds an additional hostname certificate.
     * @param hostname the hostname.
     * @param bytes the certificate (pkcs12).
     * @return this.
     */
    public Builder addCertificate(final String hostname, final byte[] bytes) {
      if (mAdditionalCertificates.containsKey(hostname)) {
        throw new IllegalStateException("Certificate for host \"" + hostname + "\" has already been set.");
      }
      mAdditionalCertificates.put(hostname, bytes);
      return this;
    }

    /**
     * Sets the only allowed protocol.
     * @param protocol the protocol.
     * @return this.
     */
    public Builder protocol(final Protocol protocol) {
      mProtocols = Collections.singletonList(protocol.name);
      return this;
    }

    /**
     * Sets the list of allowed protocols.
     * @param protocols the protocols.
     * @return this.
     */
    public Builder protocols(final Protocol[] protocols) {
      final List<String> list = new ArrayList<>(protocols.length);
      for (final Protocol protocol: protocols) {
        list.add(protocol.name);
      }
      this.mProtocols = list;
      return this;
    }

    /**
     * Sets the list of allowed cipher suites.
     * @param cipherSuites the cipher suites.
     * @return this.
     */
    public Builder cipherSuites(final String[] cipherSuites) {
      this.mCipherSuites = Arrays.asList(cipherSuites);
      return this;
    }

    /**
     * Creates the Https instance.
     * @return the Https instance.
     */
    public Https build() {
      if (mCertificate == null && mAdditionalCertificates.isEmpty()) {
        throw new IllegalStateException("At least one certificate should be specified.");
      }
      return new Https(mCertificate, mAdditionalCertificates, mProtocols, mCipherSuites, mHttp2);
    }

  }

}
