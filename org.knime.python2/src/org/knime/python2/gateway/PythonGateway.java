/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 * History
 *   Dec 18, 2019 (marcel): created
 */
package org.knime.python2.gateway;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang.SystemUtils;
import org.knime.core.data.container.CloseableRowIterator;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.port.PortObject;
import org.knime.python.typeextension.KnimeToPythonExtension;
import org.knime.python.typeextension.KnimeToPythonExtensions;
import org.knime.python.typeextension.PythonModuleExtensions;
import org.knime.python.typeextension.PythonToKnimeExtension;
import org.knime.python.typeextension.PythonToKnimeExtensions;
import org.knime.python2.Activator;
import org.knime.python2.ManualPythonCommand;
import org.knime.python2.PythonVersion;
import org.knime.python2.extensions.serializationlibrary.SerializationException;
import org.knime.python2.extensions.serializationlibrary.SerializationLibraryExtensions;
import org.knime.python2.extensions.serializationlibrary.SerializationOptions;
import org.knime.python2.extensions.serializationlibrary.interfaces.SerializationLibrary;
import org.knime.python2.extensions.serializationlibrary.interfaces.TableChunker;
import org.knime.python2.extensions.serializationlibrary.interfaces.TableIterator;
import org.knime.python2.extensions.serializationlibrary.interfaces.TableSpec;
import org.knime.python2.extensions.serializationlibrary.interfaces.impl.BufferedDataTableChunker;
import org.knime.python2.extensions.serializationlibrary.interfaces.impl.BufferedDataTableCreator;
import org.knime.python2.kernel.PythonCancelable;
import org.knime.python2.kernel.PythonCanceledExecutionException;
import org.knime.python2.kernel.PythonExecutionMonitorCancelable;
import org.knime.python2.kernel.PythonIOException;

import py4j.ClientServer;
import py4j.Py4JException;

/**
 * @author Marcel Wiedenmann, KNIME GmbH, Konstanz, Germany
 */
public final class PythonGateway implements AutoCloseable {

    private static boolean shutDown = false;

    private static PythonGateway instance;

    /**
     * TODO: currently implemented as a singleton. This probably won't work when continuing to use the current
     * {@link ClientServer} mechanism of py4j which is designed for single-threaded applications. We will either need to
     * provide a dedicated gateway (and thus, Python process) per node or switch to their multi-threaded implementation.
     * If we choose the latter (which would makes sense from a performance point of view), we need to take care of
     * Python libraries that possibly need access to the main thread of the Python process (e.g. matplotlib - but maybe
     * they only need that for plotting into an actual window. Need to investigate, there have been problems with that
     * in the Python scripting nodes).
     */
    public static synchronized PythonGateway getInstance() {
        if (instance == null) {
            if (!shutDown) {
                instance = new PythonGateway();
            } else {
                throw new IllegalStateException("Python gateway has been shut down and cannot be recreated.");
            }
        }
        return instance;
    }

    public static synchronized void shutdown() {
        if (instance != null) {
            instance.close();
            instance = null;
            shutDown = true;
        }
    }

    private ClientServer m_clientServer;

    private Process m_process;

    private EntryPoint m_entryPoint;

    private PythonGateway() {
        // Mostly copied from PythonKernel.
        final ProcessBuilder pb =
            new ManualPythonCommand(PythonVersion.PYTHON3, "/home/marcel/python-configs/knime_nodes.sh")
                .createProcessBuilder();
        final String launcherScriptPath = "/home/marcel/git/knime-python/org.knime.python2/py/knime/launcher.py";
        // Use the -u options to force Python to not buffer stdout and stderror.
        Collections.addAll(pb.command(), "-u", launcherScriptPath);
        // Add all python modules to PYTHONPATH variable.
        String existingPath = pb.environment().get("PYTHONPATH");
        existingPath = existingPath == null ? "" : existingPath;
        String externalPythonPath = PythonModuleExtensions.getPythonPath();
        externalPythonPath += File.pathSeparator + Activator.getFile(Activator.PLUGIN_ID, "py").getAbsolutePath();
        if (!externalPythonPath.isEmpty()) {
            if (existingPath.isEmpty()) {
                existingPath = externalPythonPath;
            } else {
                existingPath = existingPath + File.pathSeparator + externalPythonPath;
            }
        }
        existingPath = existingPath + File.pathSeparator;
        pb.environment().put("PYTHONPATH", existingPath);
        try {
            m_process = pb.start();
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }

        // Connect to py4j.
        // TODO: use dynamic port
        m_clientServer = new ClientServer(null);
        m_entryPoint = (EntryPoint)m_clientServer.getPythonServerEntryPoint(new Class[]{EntryPoint.class});

        // Python process requires about 150ms on my machine to start up, wait for it.
        // TODO: must of course be configurable, etc.
        boolean connected = false;
        int numAttempts = 0;
        final int numMaxAttempts = 1000;
        while (!connected) {
            if (numAttempts < numMaxAttempts) {
                try {
                    System.out.println("Connected to Python process with PID: " + m_entryPoint.getPid()
                        + ", after attempts: " + numAttempts);
                    connected = true;
                } catch (final Py4JException ex) {
                    if (!(ex.getCause() instanceof ConnectException)) {
                        throw ex;
                    } else {
                        numAttempts++;
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ex1) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } else {
                break;
            }
        }

        m_entryPoint.setSerializer(
            SerializationLibraryExtensions.getSerializationLibraryPath("org.knime.python2.serde.arrow"), this);
    }

    public EntryPoint getEntryPoint() {
        return m_entryPoint;
    }

    // Python process-wide stuff:

    private static final AtomicLong TABLE_PUT_ID = new AtomicLong();

    private static final SerializationLibrary SERIALIZER =
        SerializationLibraryExtensions.getSerializationLibrary("org.knime.python2.serde.arrow");

    public Future<String[]> getSerializerForType(final String type) {
        for (final PythonToKnimeExtension extension : PythonToKnimeExtensions.getExtensions()) {
            if (extension.getType().equals(type) || extension.getId().equals(type)) {
                return CompletableFuture.completedFuture(
                    new String[]{extension.getId(), extension.getType(), extension.getPythonSerializerPath()});
            }
        }
        return CompletableFuture.completedFuture(new String[]{"", "", ""});
    }

    public Future<String[]> getDeserializerForType(final String type) {
        for (final KnimeToPythonExtension extension : KnimeToPythonExtensions.getExtensions()) {
            if (extension.getId().equals(type)) {
                return CompletableFuture
                    .completedFuture(new String[]{extension.getId(), extension.getPythonDeserializerPath()});
            }
        }
        return CompletableFuture.completedFuture(new String[]{"", ""});
    }

    public String putTable(final BufferedDataTable table, final ExecutionMonitor monitor)
        throws PythonIOException, SerializationException, PythonCanceledExecutionException {
        final ExecutionMonitor serializationMonitor = monitor.createSubProgress(0.5);
        final ExecutionMonitor deserializationMonitor = monitor.createSubProgress(0.5);
        final int chunkSize = 50_000;
        try (final CloseableRowIterator iterator = table.iterator()) {
            if (table.size() > Integer.MAX_VALUE) {
                throw new PythonIOException(
                    "Number of rows exceeds maximum of " + Integer.MAX_VALUE + " rows for input table!");
            }
            final int rowCount = (int)table.size();
            int numberChunks = (int)Math.ceil(rowCount / (double)chunkSize);
            if (numberChunks == 0) {
                numberChunks = 1;
            }
            int rowsDone = 0;
            final TableChunker tableChunker =
                new BufferedDataTableChunker(table.getDataTableSpec(), iterator, rowCount);
            final String handle = "table" + TABLE_PUT_ID.incrementAndGet();
            for (int i = 0; i < numberChunks; i++) {
                final int rowsInThisIteration = Math.min(rowCount - rowsDone, chunkSize);
                final ExecutionMonitor chunkProgress =
                    serializationMonitor.createSubProgress(rowsInThisIteration / (double)rowCount);
                final TableIterator tableIterator =
                    ((BufferedDataTableChunker)tableChunker).nextChunk(rowsInThisIteration, chunkProgress);
                // Note: we only (efficiently) support disk-based serializers that send only the file path via the
                // socket (supporting serializers that transmit the actual data via the socket would probably require
                // writing a custom py4j command).
                final byte[] bytes = SERIALIZER.tableToBytes(tableIterator, new SerializationOptions(),
                    new PythonExecutionMonitorCancelable(monitor));
                chunkProgress.setProgress(1);
                rowsDone += rowsInThisIteration;
                serializationMonitor.setProgress(rowsDone / (double)rowCount);
                if (i == 0) {
                    m_entryPoint.deserializeNew(handle, bytes);
                } else {
                    m_entryPoint.deserializeAppend(handle, bytes);
                }
                deserializationMonitor.setProgress(rowsDone / (double)rowCount);
            }
            return handle;
        }
    }

    public PortObject getTable(final String handle, final ExecutionContext exec)
        throws SerializationException, PythonCanceledExecutionException {
        final PythonCancelable cancelable = new PythonExecutionMonitorCancelable(exec);
        final ExecutionMonitor serializationMonitor = exec.createSubProgress(0.5);
        final ExecutionMonitor deserializationMonitor = exec.createSubProgress(0.5);
        final int tableSize = m_entryPoint.getTableSize(handle);
        final int chunkSize = 50_000;
        int numberChunks = (int)Math.ceil(tableSize / (double)chunkSize);
        if (numberChunks == 0) {
            numberChunks = 1;
        }
        BufferedDataTableCreator tableCreator = null;
        for (int i = 0; i < numberChunks; i++) {
            final int start = chunkSize * i;
            final int end = Math.min(tableSize, (start + chunkSize) - 1);
            final byte[] bytes = m_entryPoint.serializeChunk(handle, start, end);
            serializationMonitor.setProgress((end + 1) / (double)tableSize);
            if (tableCreator == null) {
                final TableSpec spec = SERIALIZER.tableSpecFromBytes(bytes, cancelable);
                tableCreator = new BufferedDataTableCreator(spec, exec, deserializationMonitor, tableSize);
            }
            SERIALIZER.bytesIntoTable(tableCreator, bytes, new SerializationOptions(), cancelable);
            deserializationMonitor.setProgress((end + 1) / (double)tableSize);
        }
        return tableCreator.getTable();
    }

    // --

    @Override
    public void close() {
        // Mostly copied from PythonKernel.
        // If the original process was a script, we have to kill the actual Python process by PID.
        int pid = m_entryPoint.getPid();

        if (m_clientServer != null) {
            m_clientServer.shutdown();
            // TODO: May require further cleanup. See: https://www.py4j.org/advanced_topics.html#py4j-memory-model
            m_clientServer = null;
        }

        try {
            ProcessBuilder pb;
            if (SystemUtils.IS_OS_WINDOWS) {
                pb = new ProcessBuilder("taskkill", "/F", "/PID", "" + pid);
            } else {
                pb = new ProcessBuilder("kill", "-KILL", "" + pid);
            }
            final Process p = pb.start();
            p.waitFor();
        } catch (final InterruptedException ex) {
            // Closing the kernel should not be interrupted.
            Thread.currentThread().interrupt();
        } catch (final Exception ignore) {
            // Ignore.
        }
        if (m_process != null) {
            m_process.destroyForcibly();
            // TODO: Further action required in case the process cannot be destroyed via Java. See PythonKernel#close()
        }
    }
}
