package com.wurgobes.imagej;
/* Fast Temporal Median filter 
(c) 2017 Rolf Harkes and Bram van den Broek, Netherlands Cancer Institute.
Based on the Fast Temporal Median Filter for ImageJ by the Milstein Lab.
It implementes the T.S.Huang algorithm in a maven .jar for easy deployment in Fiji (ImageJ2)
Calculating the median from the ranked data, and processing each pixel in parallel. 
The filter is intended for pre-processing of single molecule localization data.
v2.3

Used articles:
T.S.Huang et al. 1979 - Original algorithm for median calculation

This software is released under the GPL v3. You may copy, distribute and modify 
the software as long as you track changes/dates in source files. Any 
modifications to or software including (via compiler) GPL-licensed code 
must also be made available under the GPL along with build & install instructions.
https://www.gnu.org/licenses/gpl-3.0.en.html

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.integer.UnsignedShortType;

import java.util.concurrent.atomic.AtomicInteger;

public class TemporalMedian_original {
	
	public static void main(Img<UnsignedShortType> img, int window, int offset ) {
		final int windowC = (window - 1) / 2;
		final int imgw = (int) img.dimension(0);
        final int imgh = (int) img.dimension(1);
        final int pixels = imgw * imgh;
        
		final RankMap rankmap = RankMap.build(img);
        @SuppressWarnings({ "unchecked", "rawtypes" })
		final RandomAccessibleInterval<UnsignedShortType> ranked = Converters.convert((RandomAccessibleInterval) img, rankmap::toRanked, new UnsignedShortType());
        
		
		final AtomicInteger ai = new AtomicInteger(0); //special unique int for each thread
		final Thread[] threads = newThreadArray(); //all threads
		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() { //make threads
				{
					setPriority(Thread.NORM_PRIORITY);
				}
				public void run() {
					RandomAccess<UnsignedShortType> front = ranked.randomAccess();
                    RandomAccess<UnsignedShortType> back = img.randomAccess();
                    MedianHistogram median = new MedianHistogram(window, rankmap.getMaxRank());
                    for (int j = ai.getAndIncrement(); j < pixels; j = ai.getAndIncrement()) { //get unique i
                        final int[] pos = { j % imgw, j / imgw, 0 };
                        front.setPosition(pos);
                        back.setPosition(pos);

                        // read the first window ranked pixels into median filter
                        for (int i = 0; i < window; ++i) {
                            median.add((short) front.get().get());
                            front.fwd(2);
                        }
                        // write current median for windowC+1 pixels
                        for (int i = 0; i <= windowC; ++i) {
                            final UnsignedShortType t = back.get();
                            t.set(t.get() + offset - rankmap.fromRanked(median.get()));
                            back.fwd(2);
                        }

                        final int zSize = (int)img.dimension(2);
                        final int zSteps = zSize - window;
                        for (int i = 0; i < zSteps; ++i) {
                            median.add((short) front.get().get());
                            front.fwd(2);
                            final UnsignedShortType t = back.get();
                            t.set(t.get() + offset - rankmap.fromRanked(median.get()));
                            back.fwd(2);
                        }

                        // write current median for windowC pixels
                        for (int i = 0; i < windowC; ++i) {
                            final UnsignedShortType t = back.get();
                            t.set(t.get() + offset - rankmap.fromRanked(median.get()));
                            back.fwd(2);
                        }
                    }
				}
			}; //end of thread creation
		}
		startAndJoin(threads);
	}
	private static Thread[] newThreadArray() {
		int n_cpus = Runtime.getRuntime().availableProcessors();
		return new Thread[n_cpus];
	}
	public static void startAndJoin(Thread[] threads) {
		for (int ithread = 0; ithread < threads.length; ++ithread) {
			threads[ithread].setPriority(Thread.NORM_PRIORITY);
			threads[ithread].start();
		}
		try {
			for (int ithread = 0; ithread < threads.length; ++ithread) {
				threads[ithread].join();
			}
		} catch (InterruptedException ie) {
			throw new RuntimeException(ie);
		}
	}
	
	static class RankMap
    {
        public static final int U16_SIZE = 65536;

        private final short[] inputToRanked;
        private final short[] rankedToInput;
        private static int maxRank;

        public RankMap(final short[] inputToRanked, final short[] rankedToInput) {
            this.inputToRanked = inputToRanked;
            this.rankedToInput = rankedToInput;
        }

        public static RankMap build(IterableInterval<UnsignedShortType> input)
        {
            final boolean inihist[] = new boolean[U16_SIZE];
            input.forEach(t -> inihist[t.get()] = true);

            final int mapSize = U16_SIZE;
            final short[] inputToRanked = new short[ mapSize ];
            final short[] rankedToInput = new short[ mapSize ];
            int r = 0;
            for ( int i = 0; i < inihist.length; ++i ) {
                if ( inihist[ i ] )
                {
                    rankedToInput[r] = (short) i;
                    inputToRanked[i] = (short) r;
                    ++r;
                }
            }
            maxRank = r - 1;

            return new RankMap(inputToRanked, rankedToInput);
        }

        public void toRanked(final UnsignedShortType in, final UnsignedShortType out) {
            out.set(inputToRanked[in.get()]);
        }

        public void fromRanked(final UnsignedShortType in, final UnsignedShortType out) {
            out.set(rankedToInput[in.get()]);
        }

        public short fromRanked(final short in) {
            return rankedToInput[in];
        }

        public int getMaxRank() {
            return maxRank;
        }
    }	
}