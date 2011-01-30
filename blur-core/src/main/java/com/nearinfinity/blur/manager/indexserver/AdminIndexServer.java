package com.nearinfinity.blur.manager.indexserver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.KeywordAnalyzer;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.Similarity;

import com.nearinfinity.blur.analysis.BlurAnalyzer;
import com.nearinfinity.blur.lucene.search.FairSimilarity;
import com.nearinfinity.blur.manager.IndexServer;
import com.nearinfinity.blur.manager.indexserver.DistributedManager.Value;

public abstract class AdminIndexServer implements IndexServer, ZookeeperPathContants {
    
    private static final Log LOG = LogFactory.getLog(AdminIndexServer.class);

    public static final Analyzer BLANK_ANALYZER = new BlurAnalyzer(new KeywordAnalyzer(), "");
    protected String nodeName;
    protected AtomicReference<Map<String,TABLE_STATUS>> statusMap = new AtomicReference<Map<String,TABLE_STATUS>>(new HashMap<String, TABLE_STATUS>());
    protected AtomicReference<List<String>> tableList = new AtomicReference<List<String>>(new ArrayList<String>());
    protected AtomicReference<Map<String, Analyzer>> analyzerMap = new AtomicReference<Map<String, Analyzer>>(new HashMap<String, Analyzer>());
    protected DistributedManager dm;
    protected Timer daemon;
    protected ExecutorService executorService;
    
    /**
     * All sub classes need to call super.init().
     * @return 
     */
    public void init() {
        executorService = Executors.newCachedThreadPool();
        dm.createPath(BLUR_TABLES); //ensures the path exists
        updateStatus();
        startUpdateStatusPollingDaemon();
    }
    
    public void close() {
        daemon.cancel();
        daemon.purge();
        executorService.shutdownNow();
    }

    private void startUpdateStatusPollingDaemon() {
        daemon = new Timer("AdminIndexServer-Status-Poller", true);
        daemon.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                updateStatus();
            }
        }, TimeUnit.SECONDS.toMillis(10), TimeUnit.SECONDS.toMillis(10));
    }

    private synchronized void updateStatus() {
        updateTableList();
        updateTableAnalyzers();
        updateTableStatus();
        registerCallbackForChanges();
        warmUpIndexes();
    }
    
    private void warmUpIndexes() {
        List<String> tableList = getTableList();
        for (String t : tableList) {
            final String table = t;
            if (getTableStatus(table) == TABLE_STATUS.ENABLED) {
                executorService.execute(new Runnable() {
                    @Override
                    public void run() {
                        warmUpTable(table);
                    }
                });
            }
        }
    }

    private void warmUpTable(String table) {
        try {
            LOG.debug("Warmup for table [" + table + "]");
            Map<String, IndexReader> indexReaders = getIndexReaders(table);
            LOG.debug("Warmup complete for table [" + table + "] shards [" + indexReaders.keySet() + "]");
        } catch (Exception e) {
            LOG.error("Warmup error with table [" + table + "]", e);
        }
    }

    private void registerCallbackForChanges() {
        dm.registerCallableOnChange(newRunnableUpdateStatus(), BLUR_TABLES);
        for (String table : tableList.get()) {
            dm.registerCallableOnChange(newRunnableUpdateStatus(), BLUR_TABLES,table);
        }        
    }

    private Runnable newRunnableUpdateStatus() {
        return new Runnable() {
            @Override
            public void run() {
                updateStatus();
            }
        };
    }

    private void updateTableList() {
        List<String> newTables = dm.list(BLUR_TABLES);
        List<String> oldTables = tableList.get();
        tableList.set(newTables);
        for (String table : newTables) {
            if (!oldTables.contains(table)) {
                LOG.info("Table [" + table + "] identified.");
            }
        }
        for (String table : oldTables) {
            if (!newTables.contains(table)) {
                LOG.info("Table [" + table + "] removed.");
            }
        }
    }
    
    private void updateTableAnalyzers() {
        Map<String, Analyzer> newMap = new HashMap<String, Analyzer>();
        for (String table : tableList.get()) {
            Value value = new Value();
            dm.fetchData(value, BLUR_TABLES, table);
            Analyzer analyzer;
            if (value.data == null) {
                analyzer = BLANK_ANALYZER;
            } else {
                try {
                    analyzer = BlurAnalyzer.create(new ByteArrayInputStream(value.data));
                } catch (IOException e) {
                    LOG.error("Error trying to load analyzer for table [" + table + "], using blank analyzer.");
                    analyzer = BLANK_ANALYZER;
                }
            }
            newMap.put(table, analyzer);
        }
        analyzerMap.set(newMap);
    }

    private void updateTableStatus() {
        Map<String, TABLE_STATUS> newMap = new HashMap<String, TABLE_STATUS>();
        Map<String, TABLE_STATUS> oldMap = statusMap.get();
        for (String table : tableList.get()) {
            TABLE_STATUS status;
            if (dm.exists(BLUR_TABLES,table,BLUR_TABLES_ENABLED)) {
                status = TABLE_STATUS.ENABLED;
            } else {
                status = TABLE_STATUS.DISABLED;
            }
            newMap.put(table, status);
            TABLE_STATUS oldStatus = oldMap.get(table);
            if (oldStatus == null || oldStatus != status) {
                LOG.info("Table [" + table +
                		"] change status to [" + status +
                		"]");
            }
        }
        statusMap.set(newMap);
        for (String table : oldMap.keySet()) {
            if (!newMap.containsKey(table)) {
                LOG.info("Status could not be found for table [" + table + "], possibly removed.");
            }
        }
    }

    @Override
    public final Analyzer getAnalyzer(String table) {
        Analyzer analyzer = analyzerMap.get().get(table);
        if (analyzer == null) {
            return BLANK_ANALYZER;
        }
        return analyzer;
    }

    @Override
    public final String getNodeName() {
        return nodeName;
    }

    @Override
    public final Similarity getSimilarity(String table) {
        return new FairSimilarity();
    }

    @Override
    public final List<String> getTableList() {
        return tableList.get();
    }

    @Override
    public final TABLE_STATUS getTableStatus(String table) {
        TABLE_STATUS tableStatus = statusMap.get().get(table);
        if (tableStatus == null) {
            return TABLE_STATUS.DISABLED;
        }
        return tableStatus;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public void setDistributedManager(DistributedManager distributedManager) {
        this.dm = distributedManager;
    }

}
