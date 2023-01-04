# ActiveMQ Ansible Setup

This is a fork of the MQPerf project https://github.com/softwaremill/mqperf, which has been simplified, and is designed to make starting 
and testing an ActiveMQ environment with a specific configuration as straightforward as possible.

## Pre-requisites

### Tools
Tests have been run with the following prerequisites:
- python 3.9.5 (`via pyenv`)
- ansible 2.9.5 (`pip install 'ansible==2.9.5'`)
- boto3 1.17.96 (`pip install boto3`)

### AWS Credentials
Message queues and test servers are automatically provisioned using **Ansible** on **AWS**. You will need to have the
`AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` present in the environment for things to work properly, as well
as Ansible and Boto installed.

### AWS Bucket

AWS S3 is used to distribute a number of artifacts from the machine running the Ansible scripts to the servers in EC2.
In order to do this, a suitable S3 bucket needs to be created in S3, and its name specified in `ansible/group_vars/all.yml`:

```
s3_bucket: jrg-sml-mqperf-2022
```

# Provisioning broker nodes

If your broker config makes use of a MySQL server for persistence, that should be started first:

```shell
ansible-playbook install_and_setup_mysql.yml
```

You can reference the MySQL database in the template for your ActiveMQ (which you'll need for the next step!):

```xml
  <!-- mysql db datasource configured for reconnect to ensure activemq reconnects after db outage -->
	<!-- https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-reference-configuration-properties.html -->
	<bean id="mysql-ds" class="org.apache.commons.dbcp2.BasicDataSource" destroy-method="close">
		<property name="driverClassName" value="org.mariadb.jdbc.Driver"/>
        <property name="url" value="jdbc:mariadb://{{ groups['mysql']|map('extract', hostvars, 'ec2_private_ip_address')|list|first }}/activemq?relaxAutoCommit=true&amp;autoReconnect=true&amp;initialTimeout=10&amp;maxReconnects=20"/>
		<property name="username" value="activemq"/>
		<property name="password" value="activemq"/>
		<property name="poolPreparedStatements" value="true"/> 
		<property name="fastFailValidation" value="true"/>
  		<property name="jmxName" value="org.apache.commons.dbcp2:BasicDataSource=activemq"/>
	</bean>
```

The provided config will, by default, start a failover setup using MySQL. Some other templates I used (e.g. a network-of-brokers config) are also provided in the `ansible/roles/activemq/templates` folder.
Simply copy the config you want to `activemq.xml.j2`, or tweak the `activemq.xml.j2` file as needed. Some other config options you may wish to change:

Heap Space: See "ACTIVEMQ_OPTS_MEMORY" in `env.j2`
Instance type and disk space: see "ec2_instance_type" and "root_volume_size" in `ansible/group_vars/all.yml`
Instance count: see "count" in `ansible/install_and_setup_activemq.yml`

The ansible task includes configuring remote JMX access, and includes a Micrometer Plugin (https://micrometer.io/), along with a Prometheus endpoint to get stats from the broker.

The Micrometer plugin needs to have the jar and webapp installed in the broker, and configured with the following in activemq.xml.j2:

```xml
  <plugins>
    <bean xmlns="http://www.springframework.org/schema/beans" class="com.tomitribe.activemq.metrics.MetricsPlugin" />
  </plugins>
```

and this config in jetty.xml.j2:

```xml
                                <bean class="org.eclipse.jetty.webapp.WebAppContext">
                                        <property name="contextPath" value="/metrics" />
                                        <property name="resourceBase" value="${activemq.home}/webapps/metrics" />
                                        <property name="logUrlOnStart" value="true" />
                                </bean>
```

Ensure the `securityConstraintMapping` is *not* set for `/`:

```xml
    <bean id="securityConstraintMapping" class="org.eclipse.jetty.security.ConstraintMapping">
        <property name="constraint" ref="securityConstraint" />
        <property name="pathSpec" value="/admin/*,/api/*,*.jsp,*.html,*.js,*.css,*.png,*.gif,*.ico" />
    </bean>
```

The broker(s) can be started with the following Ansible command:

```shell
ansible-playbook install_and_setup_activemq.yml
```

At this point, you can connect to the brokers you have started, and interact with them manually, or you can follow the
steps below to send/receive messages through the broker(s).

# Message generation notes
* By default, each message has length of 100 characters (this is configurable)
* For each test, we generate a pool of 10000 random messages
* Each batch of messages is constructed using messages from that pool
* Each message in the batch is modified: 13 characters from the end are replaced with stringified timestamp
  (TS value is used for measurement on the receiver end)

Please consider the above when configuring message size parameter in test configuration: ```"msg_size": 100```.
If message is too short, then majority of its content will be the TS information. For that reason, we suggest
configuring message length at 50+ characters.

# Configuring tests
Test configurations are located under `ansible/tests`. Each configuration has a number of parameters 
that may influence the test execution and its results.

# Running tests
*Note: all commands should be run in the `ansible` directory*

### Provision sender and receiver nodes
```shell
ansible-playbook provision_mqperf_nodes.yml
```
*Note: you can adjust the number of these **EC2** instances for your own tests.*

**WARNING: after each code change, you'll need to remove the fat-jars from the `target/scala-2.12` directory and re-run 
`provision_mqperf_nodes.yml`.**

### Provision Prometheus and Grafana nodes
```shell
ansible-playbook install_and_setup_prometheus.yml
```
**WARNING: this must be done each time after provisioning new sender / receiver nodes (previous step) so that Prometheus 
is properly configured to scrape the new servers for metrics**

### Monitoring tests
Metrics are gathered using **Prometheus** and visualized using **Grafana**.

Accessing monitoring dashboard:
* Lookup the *public* IP address of the EC2 node where metric tools have been deployed.
* Open `IP:3000/dashboards` in your browser
* Login with `admin/pass` credentials
* Select `MQPerf Dashboard`

### Execute test
* Choose your test configuration from the `tests` directory
* Use the file name as the `test_name` in the `run_tests.yml` file
* Run the command
```shell
ansible-playbook run_tests.yml
```

# Cleaning up
There are few commands dedicated to cleaning up the cloud resources after the tests execution.

* Stopping sender and receiver processing
```shell
ansible-playbook stop.yml
```

* Terminating EC2 instances
```shell
ansible-playbook shutdown_ec2_instances.yml
```

* Removing all MQPerf-related resources on AWS
```shell
ansible-playbook remove_aws_resources.yml
```

# Utilities
* Checking receiver/sender status
```shell
ansible-playbook check_status.yml
```

* Running sender nodes only
```shell
ansible-playbook sender_only.yml
```

* Running receiver nodes only
```shell
ansible-playbook receiver_only.yml
```

