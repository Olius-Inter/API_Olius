package br.com.olius.olius_core_api.security;

import br.com.olius.olius_core_api.exception.InvalidWebhookException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StripeWebhookSignatureVerifierTest {
    private static final String SECRET="whsec_test_only";
    private static final long NOW=1793000000L;
    private final StripeWebhookSignatureVerifier verifier=new StripeWebhookSignatureVerifier(SECRET,
            Clock.fixed(Instant.ofEpochSecond(NOW),ZoneOffset.UTC));
    private final byte[] body="{ \"id\": \"evt_example\" }".getBytes(StandardCharsets.UTF_8);

    @Test void matchesIndependentHmacFixture() {
        verifier.verify(body,"t=1793000000,v1=5b428906091fc6c9f66eeae1fe97d75a6471ccfcaaae1faf712c30520a95666b");
    }
    @Test void acceptsOriginalBytesAndMultipleV1Signatures() throws Exception {
        verifier.verify(body,"t="+NOW+",v1="+"0".repeat(64)+",v1="+sign(body,NOW));
    }
    @Test void rejectsModifiedBodyAndWrongSecret() throws Exception {
        String header="t="+NOW+",v1="+sign(body,NOW);
        assertThatThrownBy(()->verifier.verify("{}".getBytes(StandardCharsets.UTF_8),header))
                .isInstanceOf(InvalidWebhookException.class);
        var other=new StripeWebhookSignatureVerifier("whsec_other",Clock.fixed(Instant.ofEpochSecond(NOW),ZoneOffset.UTC));
        assertThatThrownBy(()->other.verify(body,header)).isInstanceOf(InvalidWebhookException.class);
    }
    @Test void rejectsOldAndFutureSignedRequests() throws Exception {
        for(long timestamp:new long[]{NOW-301,NOW+301}) {
            String header="t="+timestamp+",v1="+sign(body,timestamp);
            assertThatThrownBy(()->verifier.verify(body,header)).isInstanceOf(InvalidWebhookException.class);
        }
    }
    @Test void rejectsMissingMalformedAndOversizedHeaders() {
        for(String header:new String[]{null,"","v1=abc","t=abc,v1=abc","t=1,t=2,v1=abc","x".repeat(4097)})
            assertThatThrownBy(()->verifier.verify(body,header)).isInstanceOf(InvalidWebhookException.class);
    }
    private String sign(byte[] bytes,long timestamp) throws Exception {
        var mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update((timestamp+".").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(bytes));
    }
}
