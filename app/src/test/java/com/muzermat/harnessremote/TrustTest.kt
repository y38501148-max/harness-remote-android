package com.muzermat.harnessremote
import org.junit.Test
import org.junit.Assert.*
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.net.URLEncoder

class TrustTest {
    private val id="f60e105a-1d14-4ac0-90a2-9c1ed22c702f"
    private fun pin(cert:HeldCertificate)=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.certificate.publicKey.encoded))
    @Test fun stableKeyCertificateRenewalWorks(){val old=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();val renewed=HeldCertificate.Builder().keyPair(old.keyPair).addSubjectAlternativeName("localhost").build();PinnedTrust(pin(old)).checkServerTrusted(arrayOf(renewed.certificate),"RSA")}
    @Test fun wrongComputerRejected(){val old=HeldCertificate.Builder().build();val other=HeldCertificate.Builder().build();assertThrows(java.security.cert.CertificateException::class.java){PinnedTrust(pin(old)).checkServerTrusted(arrayOf(other.certificate),"RSA")}}
    @Test fun expiredEvenWithSameKeyRejected(){val expired=HeldCertificate.Builder().validityInterval(1,2).build();assertThrows(java.security.cert.CertificateExpiredException::class.java){PinnedTrust(pin(expired)).checkServerTrusted(arrayOf(expired.certificate),"RSA")}}
    @Test fun realHandshakeKeepsHostnameValidation(){val cert=HeldCertificate.Builder().addSubjectAlternativeName("wrong.invalid").build();val server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false);server.start();try{val client=PinnedTransport(HostProfile(id,server.url("/").toString().trimEnd('/'),pin(cert)),{""},{});assertThrows(javax.net.ssl.SSLPeerUnverifiedException::class.java){client.client.newCall(client.request("/remote/info")).execute()};client.close()}finally{server.close()}}
    @Test fun realHandshakeAndForeignOriginRejection(){val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();val server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false);server.start();try{server.enqueue(MockResponse().setBody("{\"hostId\":\"$id\",\"protocolVersion\":1,\"appTransportVersion\":1}"));val client=PinnedTransport(HostProfile(id,server.url("/").toString().trimEnd('/'),pin(cert)),{""},{});assertEquals(id,client.verify().getString("hostId"));assertThrows(IllegalArgumentException::class.java){client.request("https://example.com/secrets")};client.close()}finally{server.close()}}
    @Test fun qrExpiryAndAddressChangesRetainIdentity(){val cert=HeldCertificate.Builder().build();val now=System.currentTimeMillis();val raw="https://[2001:250::1]:8443/remote/pair#v=1&hostId=$id&pin=${URLEncoder.encode("+".repeat(42)+"8=","UTF-8")}&expires=${now+120000}&invite=${"a".repeat(43)}";val host=HostProfile.parsePairing(raw,now).first;assertEquals(host.pin,host.updateAddress("https://[2001:250::2]:8443").pin);assertThrows(IllegalArgumentException::class.java){HostProfile.parsePairing(raw,now+130000)};assertThrows(IllegalArgumentException::class.java){host.updateAddress("http://example.com")};assertThrows(IllegalArgumentException::class.java){host.updateAddress("https://user:pass@example.com")}}
}
