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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.ejb.Singleton;
import javax.ejb.Startup;

@Singleton
@Startup
public class SenderController {
    @Resource
    private SenderBean sender;

    @PostConstruct
    public void start() {
        sender.start();
    }

    @PreDestroy
    public void stop() {
        sender.stop();
    }

}
