package org.vanilladb.core.storage.log;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.vanilladb.core.sql.Constant;
import org.vanilladb.core.storage.tx.recovery.LogRecord;
import org.vanilladb.core.storage.tx.recovery.LogRecordIterator;
import org.vanilladb.core.storage.tx.recovery.ReversibleIterator;

public class NVMLogRingBuffer {
	class LogEntry {
		boolean isPersist;
		LogRecord rec;
		
		LogEntry() {
			this.isPersist = false;
			this.rec = null;
		}
	};
	
	class LogPointer {
		int idx;
		long lsn;
		
		LogPointer(int idx, long lsn) {
			this.idx = idx;
			this.lsn = lsn;
		}
	};
	
	/* Non-volatile data structures */
	private int size;
	private LogEntry[] ring;
	private LogPointer logTail, logHead;
	
	/* Volatile data structure */
	private ReadWriteLock logHeadLock = new ReentrantReadWriteLock();
	
	/* Debug */
	private PrintWriter debug;
	
	public NVMLogRingBuffer(int size, int tailIdx, long tailLsn, 
			int headIdx, long headLsn) {
		this.size = size;
		this.ring = new LogEntry[size];
		for (int i = 0; i < size; i++)
			ring[i] = new LogEntry();
		this.logTail = new LogPointer(tailIdx, tailLsn);
		this.logHead = new LogPointer(headIdx, headLsn);
		
		/* Debug */
		try {
			debug = new PrintWriter(new FileOutputStream("/home/yschang/debug.txt"));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Reconstruct non-volatile log record objects.
	 */
	public void rebuild() {
		ReversibleIterator<LogRecord> iter = new LogRecordIterator();
		long lsn = logTail.lsn - 1;
		while (iter.hasNext()) {
			LogRecord rec = iter.next();
			
			rec.setLSN(new LogSeqNum(lsn));
			insert(rec, lsn);
			lsn--;
		}
		debug.println("lsn = " + lsn + " logHead.lsn = " + logHead.lsn);
		debug.flush();
	}
	
	public LogRecord get(int idx) {
		return ring[idx].rec;
	}
	
	public void insert(LogRecord rec, long lsn) {
		logHeadLock.readLock().lock();
		int idx = logHead.idx + (int)(lsn - logHead.lsn);
		idx = idx % size;
		ring[idx].rec = rec;
		ring[idx].isPersist = true;
		logHeadLock.readLock().unlock();
	}
	
	public void checkPersistence(long lsn) {
		while (lsn >= logTail.lsn) {
			moveTailForward();
		}
	}
		
	public void moveHeadForward(List<Long> txNums) {
		logHeadLock.writeLock().lock();
		int idx = logHead.idx;
		long lsn = logHead.lsn;
		long tailLsn = logTail.lsn;
		
		while (lsn < tailLsn) {
			if (ring[idx].rec.op() == LogRecord.OP_START &&
					txNums.contains(ring[idx].rec.txNumber()))
				break;
			ring[idx].rec = null;
			ring[idx].isPersist = false;
			idx = (idx + 1) % size;
			lsn++;
		}
		logHead.idx = idx;
		logHead.lsn = lsn;
		logHeadLock.writeLock().unlock();
		debug.write("Move logHead forward.\n");
		debug.write("logHead: idx = " + logHead.idx + " lsn = " + logHead.lsn + "\n");
		debug.write("logTail: idx = " + logTail.idx + " lsn = " + logTail.lsn + "\n");
		debug.flush();
	}
	
	public void moveTailForward() {
		synchronized (logTail) {
			int idx = logTail.idx;
			long lsn = logTail.lsn;
			
			while (ring[idx].isPersist) {
				idx = (idx + 1) % size;
				lsn++;
			}
			logTail.idx = idx;
			logTail.lsn = lsn;
		}
	}
	
	public int size() {
		return size;
	}
	
	public int tailIdx() {
		return logTail.idx;
	}
	
	public long tailLsn() {
		return logTail.lsn;
	}
	
	public int headIdx() {
		return logHead.idx;
	}
	
	public long headLsn() {
		return logHead.lsn;
	}

	public void persist(LogMgr logMgr) {
		LogPosition p = null;
		for (int i = logHead.idx; i < logTail.idx; i++) {
			List<Constant> l = ring[i].rec.buildRecord();
			p = logMgr.append(l.toArray(new Constant[l.size()]));
		}
		logMgr.flush(p);
	}
}
