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

import java.util.concurrent.atomic.AtomicInteger;
import net.imglib2.*;
import net.imglib2.converter.Converters;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedIntType;

public class TemporalMedian {


    public static  < T extends RealType< T >>  void main(RandomAccessibleInterval<T> img, int window) {
		final int windowC = (window - 1) / 2;
		final int imgw = (int) img.dimension(0);
        final int imgh = (int) img.dimension(1);
        final int pixels = imgw * imgh;

        @SuppressWarnings("unchecked")
        final RankMap rankmap = RankMap.build((IterableInterval<T>) img);
        @SuppressWarnings("unchecked")
        final RandomAccessibleInterval<T> ranked = (RandomAccessibleInterval<T>) Converters.convert(img, rankmap::toRanked, new UnsignedIntType());


        final AtomicInteger ai = new AtomicInteger(0); //special unique int for each thread
		final Thread[] threads = newThreadArray(); //all threads
		for (int ithread = 0; ithread < threads.length; ithread++) {
			threads[ithread] = new Thread() { //make threads
				{
					setPriority(Thread.NORM_PRIORITY);
				}
				public void run() {

                    RandomAccess<T> front = ranked.randomAccess();
                    RandomAccess<T> back = img.randomAccess();

                    MedianHistogram median = new MedianHistogram(window, rankmap.getMaxRank());
                    for (int j = ai.getAndIncrement(); j < pixels; j = ai.getAndIncrement()) { //get unique i
                        final int[] pos = { j % imgw, j / imgw, 0 };
                        front.setPosition(pos);
                        back.setPosition(pos);

                        // read the first window ranked pixels into median filter
                        for (int i = 0; i <  window; ++i) {
                            median.add((int) front.get().getRealFloat());
                            front.fwd(2);
                        }
                        // write current median for windowC+1 pixels
                        for (int i = 0; i <=  windowC; ++i) {
                            final T t = back.get();
                            t.setReal(Math.max(t.getRealFloat() - rankmap.fromRanked(median.get()), 0));
                            back.fwd(2);
                        }

                        final int zSize = (int)img.dimension(2);
                        final int zSteps = zSize - window;
                        for (int i = 0; i <  zSteps; ++i) {
                            median.add((short) front.get().getRealFloat());
                            front.fwd(2);
                            final T t = back.get();
                            t.setReal(Math.max(t.getRealFloat() - rankmap.fromRanked(median.get()), 0));
                            back.fwd(2);
                        }

                        // write current median for windowC pixels
                        for (int i = 0; i < windowC; ++i) {
                            final T t = back.get();
                            t.setReal(Math.max((int) t.getRealFloat() - rankmap.fromRanked(median.get()), 0));
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
        for (Thread thread : threads) {
            thread.setPriority(Thread.NORM_PRIORITY);
            thread.start();
        }
		try {
            for (Thread thread : threads) {
                thread.join();
            }
		} catch (InterruptedException ie) {
			throw new RuntimeException(ie);
		}
	}


    static  class RankMap
    {


        private final int[] inputToRanked;
        private final int[] rankedToInput;
        private static int maxRank;

        public RankMap(final int[] inputToRanked, final int[] rankedToInput) {
            this.inputToRanked = inputToRanked;
            this.rankedToInput = rankedToInput;
        }

        public static < T extends RealType< T > >  RankMap build(final IterableInterval<T> input)
        {

            final int U8_SIZE = 256;
            final int U16_SIZE = 65536;

            int mapSize = 0;
            if (input.firstElement().getBitsPerPixel() == 8) {
                mapSize = U8_SIZE;
            } else {
                mapSize = U16_SIZE;
            }

            final boolean[] inihist = new boolean[mapSize];
            input.forEach(t -> inihist[(int) t.getRealFloat()] = true);

            final int[] inputToRanked = new int[ mapSize];
            final int[] rankedToInput = new int[ mapSize ];
            int r = 0;
            for ( int i = 0; i < inihist.length; ++i ) {
                if ( inihist[ i ] )
                {
                    rankedToInput[r] =  i;
                    inputToRanked[i] =  r;
                    ++r;
                }
            }
            maxRank = r - 1;

            return new RankMap(inputToRanked, rankedToInput);
        }
        /*
        public void toRanked(final UnsignedShortType in, final UnsignedShortType out) {
            out.set(inputToRanked[in.get()]);
        }

        public void fromRanked(final UnsignedShortType in, final UnsignedShortType out) {
            out.set(rankedToInput[in.get()]);
        }
        */

        public int fromRanked(final int in) {
            return rankedToInput[in];
        }

        public int getMaxRank() {
            return maxRank;
        }

        public <T extends RealType< T >> void toRanked(final T in, final UnsignedIntType out) {
            out.setReal(inputToRanked[(int) in.getRealFloat()]);

        }

        public < T extends RealType< T >>  void fromRanked(final T in, final UnsignedIntType out) {
            out.setReal(rankedToInput[(int) in.getRealFloat()]);
        }

    }


}