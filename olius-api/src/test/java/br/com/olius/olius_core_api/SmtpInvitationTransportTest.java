package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.service.AdminInvitationDelivery;
import br.com.olius.olius_core_api.service.SmtpAdminInvitationDelivery;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import static org.assertj.core.api.Assertions.*;

/** Servidor SMTP mínimo de teste, somente em loopback; não contata o Gmail. */
class SmtpInvitationTransportTest {
    @Test void realMailClientDeliversThroughLocalSmtpTransport() throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
            var executor=Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(10000);
            Future<String> received=executor.submit(() -> receive(server));
            var sender=new JavaMailSenderImpl();
            sender.setHost(server.getInetAddress().getHostAddress()); sender.setPort(server.getLocalPort());
            sender.setDefaultEncoding("UTF-8");
            var properties=sender.getJavaMailProperties();
            properties.setProperty("mail.smtp.connectiontimeout","5000");
            properties.setProperty("mail.smtp.timeout","5000");
            // Sem autenticação/TLS apenas neste servidor descartável isolado do Gmail.
            properties.setProperty("mail.smtp.auth","false");
            properties.setProperty("mail.smtp.starttls.enable","false");
            var delivery=new SmtpAdminInvitationDelivery(sender,"olius@example.invalid",
                    URI.create("https://front.example.invalid/aceite"));
            delivery.send(new AdminInvitationDelivery.Message("Ana","ana@example.invalid","b".repeat(43)));
            String raw=received.get(10,TimeUnit.SECONDS);
            var mime=new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()),
                    new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)));
            assertThat(mime.getAllRecipients()[0]).hasToString("ana@example.invalid");
            assertThat(mime.getContentType()).startsWith("text/plain");
            assertThat(mime.getContent().toString()).contains("#token="+"b".repeat(43),"24 horas");
        }
    }

    private String receive(ServerSocket server) throws IOException {
        try(var socket=server.accept();
            var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
            var writer=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.US_ASCII))) {
            socket.setSoTimeout(10000); reply(writer,"220 fixture ESMTP");
            var data=new StringBuilder(); boolean inData=false;
            for(String line;(line=reader.readLine())!=null;) {
                if(inData) {
                    if(line.equals(".")) { inData=false; reply(writer,"250 accepted"); }
                    else data.append(line).append("\r\n");
                } else if(line.startsWith("EHLO") || line.startsWith("HELO")) reply(writer,"250 fixture");
                else if(line.equals("DATA")) { reply(writer,"354 end with dot"); inData=true; }
                else if(line.equals("QUIT")) { reply(writer,"221 bye"); break; }
                else reply(writer,"250 OK");
            }
            return data.toString();
        }
    }
    private void reply(BufferedWriter writer,String text) throws IOException {
        writer.write(text+"\r\n"); writer.flush();
    }
}
