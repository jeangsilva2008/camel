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
package org.apache.camel.component.stream;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.impl.DefaultConsumer;
import org.apache.camel.impl.DefaultMessage;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.concurrent.ExecutorServiceHelper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Consumer that can read from streams
 */
public class StreamConsumer extends DefaultConsumer implements Runnable {

    private static final transient Log LOG = LogFactory.getLog(StreamConsumer.class);
    private static final String TYPES = "in,file,url";
    private static final String INVALID_URI = "Invalid uri, valid form: 'stream:{" + TYPES + "}'";
    private static final List<String> TYPES_LIST = Arrays.asList(TYPES.split(","));
    private ExecutorService executor;
    private InputStream inputStream = System.in;
    private StreamEndpoint endpoint;
    private String uri;
    private boolean initialPromptDone;

    public StreamConsumer(StreamEndpoint endpoint, Processor processor, String uri) throws Exception {
        super(endpoint, processor);
        this.endpoint = endpoint;
        this.uri = uri;
        validateUri(uri);
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();

        if ("in".equals(uri)) {
            inputStream = System.in;
        } else if ("file".equals(uri)) {
            inputStream = resolveStreamFromFile();
        } else if ("url".equals(uri)) {
            inputStream = resolveStreamFromUrl();
        }

        executor = ExecutorServiceHelper.newSingleThreadExecutor(endpoint.getEndpointUri(), true);
        executor.execute(this);
    }

    @Override
    public void doStop() throws Exception {
        // important: do not close the stream as it will close the standard system.in etc.
        executor.shutdownNow();
        executor = null;
        super.doStop();
    }

    public void run() {
        try {
            readFromStream();
        } catch (Exception e) {
            getExceptionHandler().handleException(e);
        }
    }

    private void readFromStream() throws Exception {
        Charset charset = endpoint.getCharset();
        BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, charset));
        String line;

        if (endpoint.isScanStream()) {
            // repeat scanning from stream
            while (isRunAllowed()) {
                line = br.readLine();
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Read line: " + line);
                }
                boolean eos = line == null;
                if (!eos && isRunAllowed()) {
                    processLine(line);
                }
                try {
                    Thread.sleep(endpoint.getScanStreamDelay());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } else {
            // regular read stream once until end of stream
            boolean eos = false;
            while (!eos && isRunAllowed()) {
                if (endpoint.getPromptMessage() != null) {
                    doPromptMessage();
                }

                line = br.readLine();
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Read line: " + line);
                }
                eos = line == null;
                if (!eos && isRunAllowed()) {
                    processLine(line);
                }
            }
        }
        // important: do not close the reader as it will close the standard system.in etc.
    }

    /**
     * Strategy method for processing the line
     */
    protected void processLine(Object line) throws Exception {
        Exchange exchange = endpoint.createExchange();

        Message msg = new DefaultMessage();
        msg.setBody(line);
        exchange.setIn(msg);

        getProcessor().process(exchange);
    }

    /**
     * Strategy method for prompting the prompt message
     */
    protected void doPromptMessage() {
        long delay = 0;

        if (!initialPromptDone && endpoint.getInitialPromptDelay() > 0) {
            initialPromptDone = true;
            delay = endpoint.getInitialPromptDelay();
        } else if (endpoint.getPromptDelay() > 0) {
            delay = endpoint.getPromptDelay();
        }

        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.print(endpoint.getPromptMessage());
    }

    private InputStream resolveStreamFromUrl() throws IOException {
        String u = endpoint.getUrl();
        ObjectHelper.notEmpty(u, "url");
        if (LOG.isDebugEnabled()) {
            LOG.debug("About to read from url: " + u);
        }

        URL url = new URL(u);
        URLConnection c = url.openConnection();
        return c.getInputStream();
    }

    private InputStream resolveStreamFromFile() throws IOException {
        String fileName = endpoint.getFileName();
        ObjectHelper.notEmpty(fileName, "fileName");
        if (LOG.isDebugEnabled()) {
            LOG.debug("About to read from file: " + fileName);
        }

        File file = new File(fileName);
        return new FileInputStream(file);
    }

    private void validateUri(String uri) throws IllegalArgumentException {
        String[] s = uri.split(":");
        if (s.length < 2) {
            throw new IllegalArgumentException(INVALID_URI);
        }
        String[] t = s[1].split("\\?");

        if (t.length < 1) {
            throw new IllegalArgumentException(INVALID_URI);
        }

        this.uri = t[0].trim();
        if (!TYPES_LIST.contains(this.uri)) {
            throw new IllegalArgumentException(INVALID_URI);
        }
    }

}
