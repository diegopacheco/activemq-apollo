/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.apollo.transport.vm;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.apollo.broker.Broker;
import org.apache.activemq.apollo.broker.BrokerFactory;
import org.apache.activemq.transport.Transport;
import org.apache.activemq.transport.TransportFactory;
import org.apache.activemq.transport.TransportServer;
import org.apache.activemq.transport.pipe.Pipe;
import org.apache.activemq.transport.pipe.PipeTransportFactory;
import org.apache.activemq.util.URISupport;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Implements the vm transport which behaves like the pipe transport except that
 * it can start embedded brokers up on demand.  
 * 
 * @author chirino
 *
 */
public class VMTransportFactory extends PipeTransportFactory {
	static final private Log LOG = LogFactory.getLog(VMTransportFactory.class);
	
	/**
	 * This extension of the PipeTransportServer shuts down the broker
	 * when all the connections are disconnected.
	 * 
	 * @author chirino
	 */
	private final class VmTransportServer extends PipeTransportServer {
		private final AtomicInteger refs = new AtomicInteger();
		private Broker broker;

		@Override
		protected PipeTransport cerateServerTransport(Pipe<Object> pipe) {
			return new PipeTransport(pipe.connect()) {
				@Override
				public void start() throws Exception {
					refs.incrementAndGet();
					super.start();
				}
				@Override
				public void stop() throws Exception {
					super.stop();
					if( refs.decrementAndGet() == 0 ) {
						stopBroker();
					}
				}
			};
		}

		public void setBroker(Broker broker) {
			this.broker = broker;
		}
		
		private void stopBroker() {
			try {
				this.broker.stop();
			} catch (Exception e) {
				LOG.error("Failed to stop the broker gracefully: "+e);
				LOG.debug("Failed to stop the broker gracefully: ", e);
			}
		}
	}


	private static final String DEFAULT_PIPE_NAME = Broker.DEFAULT_VIRTUAL_HOST_NAME.toString();

	@Override
	synchronized public Transport doCompositeConnect(URI location) throws Exception {

		String brokerURI = null;
		String name;
		boolean create = true;

		name = location.getHost();
		if (name == null) {
			name = DEFAULT_PIPE_NAME;
		}

		Map<String, String> options = URISupport.parseParamters(location);
		String config = (String) options.remove("broker");
		if (config != null) {
			brokerURI = config;
		}
		if ("false".equals(options.remove("create"))) {
			create = false;
		}
		if( !options.isEmpty() ) {
			throw new IllegalArgumentException("Unrecognized vm transport parameters: "+options.keySet());
		}


		PipeTransportServer server = servers.get(name);
		if (server == null && create) {
			
			// Create the broker on demand.
			Broker broker;
			if( brokerURI == null ) {
				broker = new Broker();
			} else {
				broker = BrokerFactory.createBroker(brokerURI);
			}
			
			// Remove the existing pipe severs if the broker is configured with one...  we want to make sure it 
			// uses the one we explicitly configure here.
			for (TransportServer s : broker.getTransportServers()) {
				if (s instanceof PipeTransportServer && name.equals(((PipeTransportServer) s).getName())) {
					broker.removeTransportServer(s);
				}
			}
			
			// We want to use a vm transport server impl.
			VmTransportServer vmTransportServer = (VmTransportServer) TransportFactory.bind(new URI("vm://" + name));
			vmTransportServer.setBroker(broker);
			broker.addTransportServer(vmTransportServer);
			broker.start();
			
			server = servers.get(name);
		}

		if (server == null) {
			throw new IOException("Server is not bound: " + name);
		}
		
		return server.connect();
	}


	@Override
	protected PipeTransportServer createTransportServer() {
		return new VmTransportServer();
	}
		
}