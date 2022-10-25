/*
 * Tomitribe Confidential
 *
 * Copyright Tomitribe Corporation. 2020
 *
 * The source code for this program is not published or otherwise divested
 * of its trade secrets, irrespective of what has been deposited with the
 * U.S. Copyright Office.
 */
package com.tomitribe.activemq.client;

import org.apache.activemq.ScheduledMessage;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTopic;
import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Required;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Commands {
    private static final char[] DATA = createData();
    private static final int DATA_SIZE = 1024 * 1024 * 1; // 1 MiB

    private static char[] createData() {
        final char[] validChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
        final StringBuilder sb = new StringBuilder(DATA_SIZE);
        final Random random = new Random();

        for (int i = 0; i < DATA_SIZE; i++) {
            sb.append(validChars[random.nextInt(validChars.length)]);
        }

        return sb.toString().toCharArray();
    }

    @Command("consume")
    public void consume(@Required @Option("uri") final String uri,
                        @Required @Option("dest") final String destination,
                        @Option("username") final String username,
                        @Option("password") final String password,
                        @Option("selector") @Default("") final String selector,
                        @Option("client") final String client,
                        @Option("connections") @Default("1") final Integer connections) throws Exception {

        final CountDownLatch latch = new CountDownLatch(connections);

        final Runnable r = () -> {
            final ConnectionFactory cf = getConnectionFactory(uri);

            try (final Connection conn = getConnection(cf, username, password, client);
                 final Session sess = conn.createSession(false, Session.CLIENT_ACKNOWLEDGE)) {

                conn.start();
                Destination dest = getDestination(uri, destination);

                MessageConsumer consumer = null;

                if (client != null && Topic.class.isInstance(dest)) {
                    if (selector != null && selector.trim().length() > 1) {
                        consumer = sess.createDurableSubscriber((Topic) dest, client + "subscription", selector, true);
                    } else {
                        consumer = sess.createDurableSubscriber((Topic) dest, client + "subscription");
                    }
                }

                if (consumer == null) {
                    if (selector != null && selector.trim().length() > 1) {
                        consumer = sess.createConsumer(dest, selector);
                    } else {
                        consumer = sess.createConsumer(dest);
                    }
                }

                while (true) {
                    final TextMessage msg = (TextMessage) consumer.receive();
                    //System.out.println(msg.getText());
                    msg.acknowledge();
                }
            } catch (JMSException e) {
                e.printStackTrace();
            }

            latch.countDown();
        };

        for (int i = 0; i < connections; i++) {
            new Thread(r).start();
        }

        latch.await(1, TimeUnit.HOURS);

    }

    @Command("bad-consumer")
    public void simulateBadConsumer(@Required @Option("uri") final String uri,
                        @Required @Option("dest") final String destination,
                        @Option("username") final String username,
                        @Option("password") final String password) {

        final ConnectionFactory cf = getConnectionFactory(uri);

        try (final Connection conn = getConnection(cf, username, password, null);
             final Session sess = conn.createSession(false, Session.CLIENT_ACKNOWLEDGE)) {

            conn.start();
            Destination dest = getDestination(uri, destination);
            MessageConsumer consumer = sess.createConsumer(dest);

            while (true) {
                final TextMessage msg = (TextMessage) consumer.receive();
                System.out.println("Retrieved (but not acknowledged) message:");
                System.out.println(msg.getText());
            }
        } catch (JMSException e) {
            e.printStackTrace();
        }
    }

    @Command("bad-consumer-pending")
    public void simulateBadConsumerPending(@Required @Option("uri") final String uri,
                                           @Required @Option("dest") final String destination,
                                           @Option("username") final String username,
                                           @Option("password") final String password) {

        final ConnectionFactory cf = getConnectionFactory(uri);

        try (final Connection conn = getConnection(cf, username, password, null);
             final Session sess = conn.createSession(false, Session.CLIENT_ACKNOWLEDGE)) {

            conn.start();
            Destination dest = getDestination(uri, destination);
            MessageConsumer consumer = sess.createConsumer(dest);

            while (true) {
                final TextMessage msg = (TextMessage) consumer.receive();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (JMSException e) {
            e.printStackTrace();
        }
    }

    @Command("bad-consumer-no-tx")
    public void simulateBadConsumerNoTx(@Required @Option("uri") final String uri,
                                    @Required @Option("dest") final String destination,
                                    @Option("username") final String username,
                                    @Option("password") final String password) {

        final ConnectionFactory cf = getConnectionFactory(uri);

        try (final Connection conn = getConnection(cf, username, password, null);
             final Session sess = conn.createSession(true, Session.SESSION_TRANSACTED)) {

            conn.start();
            Destination dest = getDestination(uri, destination);
            MessageConsumer consumer = sess.createConsumer(dest);

            while (true) {
                final TextMessage msg = (TextMessage) consumer.receive();
                System.out.println("Retrieved (but not committed) message:");
                System.out.println(msg.getText());
            }
        } catch (JMSException e) {
            e.printStackTrace();
        }
    }

    private Destination getDestination(String uri, final String destination) {
        if (destination == null) {
            throw new NullPointerException("Destination cannot be null");
        }

        final Properties p = new Properties();
        p.setProperty(Context.INITIAL_CONTEXT_FACTORY, "org.apache.activemq.jndi.ActiveMQInitialContextFactory");
        p.setProperty(Context.PROVIDER_URL, uri);

        try {
            final InitialContext initialContext = new InitialContext(p);
            return (Destination) initialContext.lookup(destination);
        } catch (Exception e) {
            // try and do this without JNDI
        }

        if (destination.toLowerCase().startsWith("queue://")) {
            return new ActiveMQQueue(destination.substring(8));
        }

        if (destination.toLowerCase().startsWith("topic://")) {
            return new ActiveMQTopic(destination.substring(8));
        }

        throw new RuntimeException(destination + " not found");
    }

    @Command("produce")
    public void produce(@Required @Option("uri") final String uri,
                        @Required @Option("dest") final String destination,
                        @Required @Option("message") final String payload,
                        @Required @Option("count") final Integer count,
                        @Option("username") final String username,
                        @Option("password") final String password,
                        @Option("header") final String[] headers,
                        @Option("delay") final Long delay,
                        @Option("period") final Long period,
                        @Option("repeat") final Integer repeat,
                        @Option("threads") @Default("1") final Integer threads) {

        final ExecutorService threadPool = Executors.newFixedThreadPool(threads);

        final ConnectionFactory cf = getConnectionFactory(uri);
        final Destination dest = getDestination(uri, destination);

        try (final Connection conn = getConnection(cf, username, password, null);
             final Session sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
             final MessageProducer producer = sess.createProducer(dest)) {

            conn.start();
            final CountDownLatch latch = new CountDownLatch(count);

            for (int i = 0; i < count; i++) {
                threadPool.submit(() -> {
                    doRun(payload, headers, delay, period, repeat, sess, producer, dest);
                    latch.countDown();
                });
            }

            latch.await();
            threadPool.shutdown();

        } catch (JMSException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void doRun(
            final String payload,
            final String[] headers,
            final Long delay,
            final Long period,
            final Integer repeat,
            final Session sess,
            final MessageProducer producer,
            final Destination dest) {

        while (true) {
            try {
                final TextMessage textMessage = sess.createTextMessage(payload);

                if (headers != null) {
                    for (final String header : headers) {
                        if (!header.contains(":")) continue;

                        final String key = header.substring(0, header.indexOf(":"));
                        final String value = header.substring(header.indexOf(":") + 1);

                        textMessage.setStringProperty(key, value);
                    }
                }

                if (delay != null) {
                    textMessage.setLongProperty(ScheduledMessage.AMQ_SCHEDULED_DELAY, delay);
                }

                if (period != null) {
                    textMessage.setLongProperty(ScheduledMessage.AMQ_SCHEDULED_PERIOD, period);
                }

                if (repeat != null) {
                    textMessage.setIntProperty(ScheduledMessage.AMQ_SCHEDULED_REPEAT, repeat);
                }

                producer.send(textMessage);
                return;
            } catch (JMSException e) {
                e.printStackTrace();
            }
        }
    }

    @Command("bad-produce")
    public void badProduce(@Required @Option("uri") final String uri,
                        @Required @Option("dest") final String destination,
                        @Required @Option("message") final String payload,
                        @Option("username") final String username,
                        @Option("password") final String password) throws Exception {

        final CountDownLatch latch = new CountDownLatch(1);

        Runnable r= () -> {

            try {
                final ConnectionFactory cf = getConnectionFactory(uri);
                final Connection conn = getConnection(cf, username, password, null);
                final Session sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);

                conn.start();
                Destination dest = getDestination(uri, destination);
                final MessageProducer producer = sess.createProducer(dest);
                producer.send(sess.createTextMessage(payload));

                Thread.sleep(20 * 60 * 1000);
                latch.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }

        };

        new Thread(r).start();
        latch.await(1, TimeUnit.HOURS);

    }

    @Command("produce-random")
    public void produce(@Required @Option("uri") final String uri,
                        @Required @Option("dest") final String destination,
                        @Required @Option("message-size") final Integer payloadSize,
                        @Required @Option("count") final Integer count,
                        @Option("username") final String username,
                        @Option("password") final String password) {

        final ConnectionFactory cf = getConnectionFactory(uri);

        try (final Connection conn = getConnection(cf, username, password, null);
             final Session sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {

            conn.start();
            Destination dest = getDestination(uri, destination);
            final MessageProducer producer = sess.createProducer(dest);

            for (int i = 0; i < count; i++) {
                final String payload = createRandomPayload(payloadSize);
                producer.send(sess.createTextMessage(payload));
            }

        } catch (JMSException e) {
            e.printStackTrace();
        }
    }

    private String createRandomPayload(final Integer payloadSize) {
        final Random random = new Random();
        final StringBuilder sb = new StringBuilder(payloadSize);
        sb.append("*");

        int remaining = payloadSize - 2;
        while (remaining > 0) {
            final int offset = random.nextInt(DATA.length);
            if ((DATA.length - offset) > remaining) {
                sb.append(DATA, offset, remaining);
                remaining = 0;
            } else {
                System.out.println(String.format("Size: %d, offset: %d, length: %d, end %d", DATA.length, offset, (DATA.length - offset - 1), offset + (DATA.length - offset - 1)));
                sb.append(DATA, offset, (DATA.length - offset));
                remaining -= (DATA.length - offset);
            }
        }

        sb.append("*");
        return sb.toString();
    }

    private Connection getConnection(final ConnectionFactory cf, final String username, final String password, String client) throws JMSException {
        Connection conn;

        if (username == null && password == null) {
            conn = cf.createConnection();
        } else {
            conn = cf.createConnection(username, password);
        }

        if (conn != null && client != null) {
            conn.setClientID(client);
        }

        return conn;
    }

    private ConnectionFactory getConnectionFactory(final String uri) {
        try {
            ConnectionFactory cf;
            final Properties p = new Properties();
            p.setProperty(Context.INITIAL_CONTEXT_FACTORY, "org.apache.activemq.jndi.ActiveMQInitialContextFactory");
            p.setProperty(Context.PROVIDER_URL, uri);

            final InitialContext initialContext = new InitialContext(p);
            cf = (ConnectionFactory) initialContext.lookup("ConnectionFactory");
            return cf;
        } catch (NamingException e) {
            return null;
        }
    }



}
