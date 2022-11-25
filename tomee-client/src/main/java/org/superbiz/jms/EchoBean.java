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

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

public class EchoBean implements MessageListener {
    @Override
    public void onMessage(final Message message) {
        if (! (message instanceof TextMessage)) {
            return;
        }

        try {
            final TextMessage textMessage = (TextMessage) message;
            final String text = textMessage.getText();

        } catch (final JMSException e) {
            e.printStackTrace();
        }
    }
}
