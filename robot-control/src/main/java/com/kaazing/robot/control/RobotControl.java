/**
 * Copyright (c) 2007-2013, Kaazing Corporation. All rights reserved.
 */

package com.kaazing.robot.control;

import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.kaazing.robot.control.command.AbortCommand;
import com.kaazing.robot.control.command.Command;
import com.kaazing.robot.control.command.PrepareCommand;
import com.kaazing.robot.control.command.StartCommand;
import com.kaazing.robot.control.event.CommandEvent;
import com.kaazing.robot.control.event.ErrorEvent;
import com.kaazing.robot.control.event.FinishedEvent;
import com.kaazing.robot.control.event.PreparedEvent;
import com.kaazing.robot.control.event.StartedEvent;

public class RobotControl {

    private static final Pattern HEADER_PATTERN = Pattern.compile("([a-z\\-]+):([^\n]+)");
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final URL location;
    private URLConnection connection;

    RobotControl(URL location) {
        this.location = location;
    }

    public void connect() throws Exception {
        connection = location.openConnection();
        connection.connect();
    }

    public void disconnect() throws Exception {

        if (connection != null) {
            try {
                if (connection instanceof Closeable) {
                    ((Closeable) connection).close();
                }
                else {
                    try {
                        connection.getInputStream().close();
                    }
                    catch (IOException e) {
                        // ignore
                    }

                    try {
                        connection.getOutputStream().close();
                    }
                    catch (IOException e) {
                        // ignore
                    }
                }
            }
            finally {
                connection = null;
            }
        }
    }

    public void writeCommand(Command command) throws Exception {

        checkConnected();

        switch (command.getKind()) {
        case PREPARE:
            writecommand((PrepareCommand) command);
            break;
        case START:
            writeCommand((StartCommand) command);
            break;
        case ABORT:
            writeCommand((AbortCommand) command);
            break;
        default:
            throw new IllegalArgumentException("Urecognized command kind: " + command.getKind());
        }
    }

    public CommandEvent readEvent() throws Exception {
        // defaults to infinite
        return readEvent(0, MILLISECONDS);
    }

    public CommandEvent readEvent(int timeout, TimeUnit unit) throws Exception {

        checkConnected();

        connection.setReadTimeout((int) unit.toMillis(timeout));

        InputStream bytesIn = connection.getInputStream();
        CharsetDecoder decoder = UTF_8.newDecoder();
        BufferedReader textIn = new BufferedReader(new InputStreamReader(bytesIn, decoder));

        String eventType = textIn.readLine();
        switch (eventType.charAt(0)) {
        case 'P':
            if ("PREPARED".equals(eventType)) {
                return readPreparedEvent(textIn);
            }
            break;
        case 'S':
            if ("STARTED".equals(eventType)) {
                return readStartedEvent(textIn);
            }
            break;
        case 'E':
            if ("ERROR".equals(eventType)) {
                return readErrorEvent(textIn);
            }
            break;
        case 'F':
            if ("FINISHED".equals(eventType)) {
                return readFinishedEvent(textIn);
            }
            break;
        }

        throw new IllegalStateException("Invalid protocol frame: " + eventType);
    }

    private void checkConnected() throws Exception {
        if (connection == null) {
            throw new IllegalStateException("Not connected");
        }
    }

    private void writecommand(PrepareCommand prepare) throws Exception {
        OutputStream bytesOut = connection.getOutputStream();
        CharsetEncoder encoder = UTF_8.newEncoder();
        Writer textOut = new OutputStreamWriter(bytesOut, encoder);

        String name = prepare.getName();
        String script = prepare.getScript();
        // note: this assumes bytes-length == string-length (ASCII)
        int length = script.length();

        textOut.append("PREPARE\n");
        textOut.append(format("name:%s\n", name));
        textOut.append(format("content-length:%d\n", length));
        textOut.append("\n");
        textOut.append(script);
        textOut.flush();
    }

    private void writeCommand(StartCommand start) throws Exception {

        OutputStream bytesOut = connection.getOutputStream();
        CharsetEncoder encoder = UTF_8.newEncoder();
        Writer textOut = new OutputStreamWriter(bytesOut, encoder);

        String name = start.getName();

        textOut.append("START\n");
        textOut.append(format("name:%s\n", name));
        textOut.append("\n");
        textOut.flush();
    }

    private void writeCommand(AbortCommand abort) throws IOException, CharacterCodingException {
        OutputStream bytesOut = connection.getOutputStream();
        CharsetEncoder encoder = UTF_8.newEncoder();
        Writer textOut = new OutputStreamWriter(bytesOut, encoder);

        String name = abort.getName();

        textOut.append("ABORT\n");
        textOut.append(format("name:%s\n", name));
        textOut.append("\n");
        textOut.flush();
    }

    private PreparedEvent readPreparedEvent(BufferedReader textIn) throws IOException {
        PreparedEvent prepared = new PreparedEvent();
        String line;
        do {
            line = textIn.readLine();
            Matcher matcher = HEADER_PATTERN.matcher(line);
            if (matcher.matches()) {
                String headerName = matcher.group(1);
                String headerValue = matcher.group(2);
                switch (headerName.charAt(0)) {
                case 'n':
                    if ("name".equals(headerName)) {
                        prepared.setName(headerValue);
                    }
                    break;
                default:
                    throw new IllegalStateException("Unrecognized event header: " + headerName);
                }
            }
        } while (!line.isEmpty());
        return prepared;
    }

    private StartedEvent readStartedEvent(BufferedReader textIn) throws IOException {
        StartedEvent started = new StartedEvent();
        String line;
        do {
            line = textIn.readLine();
            Matcher matcher = HEADER_PATTERN.matcher(line);
            if (matcher.matches()) {
                String headerName = matcher.group(1);
                String headerValue = matcher.group(2);
                switch (headerName.charAt(0)) {
                case 'n':
                    if ("name".equals(headerName)) {
                        started.setName(headerValue);
                    }
                    break;
                default:
                    throw new IllegalStateException("Unrecognized event header: " + headerName);
                }
            }
        } while (!line.isEmpty());
        return started;
    }

    private FinishedEvent readFinishedEvent(BufferedReader textIn) throws IOException {
        FinishedEvent finished = new FinishedEvent();
        String line;
        int length = -1;
        do {
            line = textIn.readLine();
            Matcher matcher = HEADER_PATTERN.matcher(line);
            if (matcher.matches()) {
                String headerName = matcher.group(1);
                String headerValue = matcher.group(2);
                switch (headerName.charAt(0)) {
                case 'c':
                    if ("content-length".equals(headerName)) {
                        length = parseInt(headerValue);
                    }
                    break;
                case 'n':
                    if ("name".equals(headerName)) {
                        finished.setName(headerValue);
                    }
                    break;
                default:
                    throw new IllegalStateException("Unrecognized event header: " + headerName);
                }
            }
        } while (!line.isEmpty());

        // note: this assumes bytes-length == string-length (ASCII)
        // note: zero-length script should be non-null
        if (length >= 0) {
            finished.setScript(readContent(textIn, length));
        }

        return finished;
    }

    private ErrorEvent readErrorEvent(BufferedReader textIn) throws IOException {
        ErrorEvent error = new ErrorEvent();
        String line;
        int length = 0;
        do {
            line = textIn.readLine();
            Matcher matcher = HEADER_PATTERN.matcher(line);
            if (matcher.matches()) {
                String headerName = matcher.group(1);
                String headerValue = matcher.group(2);
                switch (headerName.charAt(0)) {
                case 'c':
                    if ("content-length".equals(headerName)) {
                        length = parseInt(headerValue);
                    }
                    break;
                case 'n':
                    if ("name".equals(headerName)) {
                        error.setName(headerValue);
                    }
                    break;
                case 's':
                    if ("summary".equals(headerName)) {
                        error.setSummary(headerValue);
                    }
                    break;
                default:
                    throw new IllegalStateException("Unrecognized event header: " + headerName);
                }
            }
        } while (!line.isEmpty());

        // note: this assumes bytes-length == string-length (ASCII)
        if (length > 0) {
            error.setDescription(readContent(textIn, length));
        }

        return error;
    }

    private String readContent(final BufferedReader textIn, final int length) throws IOException {
        final char[] content = new char[length];
        int bytesRead = 0;
        do {
            int result = textIn.read(content, bytesRead, length - bytesRead);
            if (result == -1) {
                throw new EOFException("EOF detected before all content read");
            }
            bytesRead += result;
        } while (bytesRead != length);
        return new String(content);
    }
}