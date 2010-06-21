/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.LinkedList;

import freenet.support.io.Closer;

/** Creates and manages decompressor threads. This class is 
 * given all decompressors which should be applied to an
 * InputStream via addDecompressor. The decompressors will be
 * strung together and executed when the execute method is called.
 * This class also stores any errors which may arise.
 * @author sajack
*/
public class DecompressorThreadManager {

	final LinkedList<DecompressorThread> threads;
	InputStream input;
	PipedOutputStream output = new PipedOutputStream();
	final long maxLen;
	private boolean finished = false;
	private Exception error = null;

	/** Creates a new DecompressorThreadManager
	 * @param input The stream that will be decompressed, if compressed
	 * @param maxLen The maximum number of bytes to extract
	 */
	public DecompressorThreadManager(InputStream input, long maxLen) throws IOException {
		threads = new LinkedList<DecompressorThread>();
		this.maxLen = maxLen;
		this.input = input;
		if(input == null) {
			IOException e = new IOException("Input stream may not be null");
			onFailure(e);
			throw e;
		}
	}

	/**Queues a decompressor to be ran against the input stream
	 * @param compressor The object whose decompress method will be executed
	 * @throws IOException
	 */
	public synchronized void addDecompressor(Compressor compressor) throws IOException {
		if(compressor == null) {
			IOException e = new IOException("Compressor may not be null");
			onFailure(e);
			throw e;
		}
		DecompressorThread thread = new DecompressorThread(compressor, this, input, output, maxLen);
		threads.add(thread);
		input = new PipedInputStream(output);
		output = new PipedOutputStream();
	}

	/** Creates and executes a new thread for each decompressor,
	 * chaining the output of the previous to the next.
	 * @return An InputStream from which uncompressed data may be read from
	 */
	public synchronized InputStream execute() throws Exception{
		if(getError() != null) throw getError();
		if(threads.isEmpty()) {
			onFinish();
			return input;
		}
		try {
			int count = 0;
			while(!threads.isEmpty()){
				if(getError() != null) throw getError();
				DecompressorThread threadRunnable = threads.remove();
				if(threads.isEmpty()) threadRunnable.setLast();
				new Thread(threadRunnable, "DecompressorThread"+count).start();
				count++;
			}
			output.close();			
		} catch(Exception e) {
			onFailure(e);
		}
		finally {
			Closer.close(output);
		}
		return input;
		
	}

	/** Informs the manager that a nonrecoverable exception has occured in the
	 * decompression threads
	 * @param e The thrown exception
	 */
	public synchronized void onFailure(Exception e) {
		error = e;
		onFinish();
	}

	/** Marks that the decompression of the stream has finished and wakes
	 * threads blocking on completion */
	public synchronized void onFinish() {
		finished = true;
		notifyAll();
	}

	/** Blocks until all threads have finished executing and cleaning up.*/
	public synchronized void waitFinished() {
		while(!finished) {
			try {
				wait();
			} catch(InterruptedException e) {
				//Do nothing
			}
		}
	}

	/** Returns an exception which was thrown during decompression
	 * @return Returns an exception which was caught during the decompression
	 */
	public synchronized Exception getError() {
		return error;
	}

}