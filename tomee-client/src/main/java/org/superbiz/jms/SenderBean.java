/*
 * Tomitribe Confidential
 *
 * Copyright Tomitribe Corporation. 2020
 *
 * The source code for this program is not published or otherwise divested
 * of its trade secrets, irrespective of what has been deposited with the
 * U.S. Copyright Office.
 */
package org.superbiz.jms;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.naming.InitialContext;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SenderBean {

    private int threads = 0;

    private ExecutorService threadPool = null;
    private AtomicBoolean stopped = new AtomicBoolean(true);

    public void start() {
        if (!stopped.get()) {
            return;
        }

        stopped.set(false);
        threadPool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            final int queueNum = i;

            threadPool.submit(() -> {
                try {
                    final InitialContext initialContext = new InitialContext();
                    final ConnectionFactory cf = (ConnectionFactory) initialContext.lookup("openejb:Resource/MyJmsConnectionFactory");
                    final Connection connection = cf.createConnection();

                    final Session session = connection.createSession();
                    final Queue queue = session.createQueue("TEST" + (queueNum + 1));
                    final MessageProducer producer = session.createProducer(queue);
                    while (!stopped.get()) {

                        final TextMessage textMessage = session.createTextMessage("Lorem ipsum dolor sit amet, consectetur adipiscing elit. Donec " +
                                "sollicitudin nisi eros, eget suscipit lacus gravida ut. Morbi posuere tortor vel metus " +
                                "dignissim volutpat ut in sem. Nam vel vulputate augue. Donec vel mi quis augue euismod " +
                                "tempor. Morbi accumsan elit a lorem maximus, sit amet iaculis urna finibus. Sed " +
                                "tristique viverra eros, nec tempus arcu. Vivamus sed sem et ante aliquet condimentum " +
                                "ut at leo. Aenean dignissim dolor erat, sit amet porttitor metus commodo vel. " +
                                "Vestibulum quam est, semper ultrices diam consectetur, aliquam posuere lacus. " +
                                "Vivamus sagittis erat lectus, quis posuere purus euismod quis. Proin gravida maximus " +
                                "fermentum. Integer congue ullamcorper diam. Nullam urna nunc, sagittis nec eleifend " +
                                "non, posuere quis elit. Nam rhoncus pharetra lobortis. Praesent feugiat suscipit tortor.");

                        producer.send(textMessage);
                    }

                    producer.close();
                    session.close();
                    connection.close();
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            });
        }
    }

    public void stop() {
        stopped.set(true);
        threadPool.shutdownNow();
        try {
            threadPool.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        threadPool = null;
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }
}
