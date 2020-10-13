package com.wurgobes.ftm2;
/* Faster Temporal Median filter
(c) 2020 Martijn Gobes, Holhbein Lab, Wageningen University
Based on the Fast Temporal Median Filter for ImageJ by the Netherlands Cancer Institute.

This is mostly a boilerplate script that makes small adjustments on the original
to allow for processing any bitdepth.

Calculating the median from the ranked data, and processing each pixel in parallel. 
The filter is intended for pre-processing of single molecule localization data.
v1.0

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
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedIntType;
import net.imglib2.type.numeric.integer.UnsignedShortType;

import static ij.util.ThreadUtil.createThreadArray;
import static ij.util.ThreadUtil.startAndJoin;
import static java.lang.Math.abs;
import static net.imglib2.util.Util.max;
import static net.imglib2.util.Util.min;

public class TemporalMedian {
    public static  < T extends RealType< T >>  void main(RandomAccessibleInterval<T> img, int window, int bit_depth) {
		final int windowC = (window - 1) / 2; //This is the Index of the median
		final int imgw = (int) img.dimension(0); // width of frame
        final int imgh = (int) img.dimension(1); // height of frame
        final int pixels = imgw * imgh; // Total amount of pixels

        // Build the rankmap from the image and use that to convert the original image
        // This compacts the image and reduces the memory footprint required.
        // This effectively removes all zero values from the histogram
        @SuppressWarnings("unchecked")
        final RankMap rankmap = RankMap.build((IterableInterval<T>) img);
        RandomAccessibleInterval<T> temp;

        switch(bit_depth){
            case 16:
                temp = (RandomAccessibleInterval<T>) Converters.convert(img, rankmap::toRanked, new UnsignedShortType());
                break;
            case 8:
                temp = (RandomAccessibleInterval<T>) Converters.convert(img, rankmap::toRanked, new UnsignedByteType());
                break;
            default:
                temp = (RandomAccessibleInterval<T>) Converters.convert(img, rankmap::toRanked, new UnsignedIntType());
        }

        final RandomAccessibleInterval<T> ranked = temp;

        final AtomicInteger ai = new AtomicInteger(0); //Atomic Integer is a thread safe incremental integer
        final Thread[] threads = createThreadArray(); //Get maximum of threads
        //Set the run function for each thread
		for (int ithread = 0; ithread < threads.length; ithread++) {
            threads[ithread] = new Thread(() -> {

                // Get the RandomAccess, twice for the same image
                RandomAccess<T> front = ranked.randomAccess(); // front is used to read new values
                RandomAccess<T> back = img.randomAccess(); // back is used to set the median corrected values
                MedianHistogram median = new MedianHistogram(window, rankmap.getMaxRank());
                for (int j = ai.getAndIncrement(); j < pixels; j = ai.getAndIncrement()) { //get unique i

                    final int[] pos = { j % imgw, j / imgw, 0 }; //Get position based on j
                    front.setPosition(pos); // Set the starting position for the front
                    back.setPosition(pos); // Set the starting position for the back

                    // read the first window ranked pixels into median filter

                    for (int i = 0; i <  window; ++i) {
                        // Get the next value and add it to the median object or the startingvalues
                        median.add((short) front.get().getRealFloat());
                        front.fwd(2); // Move the front one forward in the 2nd dimension to the next slice
                    }


                    int temp_median = rankmap.fromRanked((short) median.get()); // The median won't change so we read it once
                    // write current median for windowC+1 pixels
                    for (int i = 0; i <=  windowC; ++i) {
                        final T t = back.get(); // Get the reference to the back's pixel
                        t.setReal(Math.max(t.getRealFloat() - temp_median, 0)); // Set the back's value, median adjusted
                        back.fwd(2); // Move the back one forward in the 2nd dimension to the next slice
                    }

                    final int zSize = (int)img.dimension(2);
                    final int zSteps = zSize - window;
                    for (int i = 0; i <  zSteps; ++i) {
                        median.add((int) front.get().getRealFloat());
                        front.fwd(2); // Move the front one forward in the 2nd dimension to the next slice
                        final T t = back.get(); // Get the reference to the back's pixel
                        t.setReal(Math.max(t.getRealFloat() - rankmap.fromRanked( median.get()), 0)); // Set the back's value, median adjusted
                        back.fwd(2); // Move the front one forward in the 2nd dimension to the next slice
                    }

                    temp_median = rankmap.fromRanked((short) median.get());
                    // write current median for windowC pixels
                    for (int i = 0; i < windowC; ++i) {
                        final T t = back.get(); // Get the reference to the back's pixel
                        t.setReal(Math.max((int) t.getRealFloat() - temp_median, 0)); // Set the back's value, median adjusted
                        back.fwd(2); // Move the back one forward in the 2nd dimension to the next slice
                    }
                }

            }); //end of thread creation
		}
		// Start actual processing
        startAndJoin(threads);
	}

    static class  RankMap
    {
        // Two arrays that keep references to each others indices
        // This allows for compacting of the values by not recording places with no values
        private final int[] inputToRanked;
        private final int[] rankedToInput;

        private static double maximum = 0;
        private static double minimum = 0;

        static boolean isFloat;

        private static int maxRank; // Maximum value in the input

        final static int U32_SIZE = 4_000_000;

        // Simple Constructor for Rankmap, dont call this, but call build()
        public RankMap(final int[] inputToRanked, final int[] rankedToInput) {
            this.inputToRanked = inputToRanked;
            this.rankedToInput = rankedToInput;
        }

        // This is the real constructor
        // It is only called once per thread
        public static < T extends RealType< T > >  RankMap build(final IterableInterval<T> input)
        {
            // this denotes the maximum unique values
            // It will never be this high, but better be safe
            final int U8_SIZE = 256;
            final int U16_SIZE = 65536;

            // Set mapSize to what bit-depth you have

            final int mapSize;
            if (input.firstElement().getBitsPerPixel() == 8) {
                mapSize = U8_SIZE;
            } else if (input.firstElement().getBitsPerPixel() == 32) {
                mapSize = U32_SIZE;
            } else {
                mapSize = U16_SIZE;
            }

            // In inihist (Initialise Histogram) mark each existing value
            final boolean[] inihist = new boolean[mapSize];


            if(abs(input.firstElement().getRealFloat())%1.0> 0.0) {
                // This method only supports integer values
                // 32b images can be float however
                // this creates a mapping from the original values between 0 and U32_SIZE
                // This loses image precision, but how much depends on the range of values in input
                // I recommend converting it to 32b Integers to prevent this loss
                isFloat = true;
                double[] values = new double[3];
                input.realMin(values);
                minimum = min(values);
                input.realMax(values);
                maximum = max(values);

                //For each value, mark the inihist index true
                input.forEach(t -> inihist[(int) ((t.getRealFloat() - minimum) * (U32_SIZE) / (maximum - minimum))] = true);
            } else {
                isFloat = false;
                //For each value, mark the inihist index true
                input.forEach(t -> inihist[(int) t.getRealFloat()] = true);
            }



            final int[] inputToRanked = new int[ mapSize ];
            final int[] rankedToInput = new int[ mapSize ];

            // Create a map between all values and them ranked
            // This effectively concatenates it all
            // The maxRank is the maximum value in the entire image
            int r = 0;
            for ( int i = 0; i < inihist.length; ++i ) {
                if ( inihist[ i ] ) {
                    rankedToInput[r] =  i;
                    inputToRanked[i] =  r;
                    ++r;
                }
            }
            maxRank = r - 1;

            return new RankMap(inputToRanked, rankedToInput);
        }

        public int fromRanked(final int in) {
            return rankedToInput[in];
        }

        public int getMaxRank() {
            return maxRank;
        }

        public <T extends RealType< T >> void toRanked(final T in, final UnsignedIntType out) {
            if(isFloat){
                out.setReal(inputToRanked[(int) ((in.getRealFloat() - minimum) * (U32_SIZE) / (maximum - minimum))]);
            } else {
                out.setReal(inputToRanked[(int) in.getRealFloat()]);
            }
        }

        public <T extends RealType< T >> void toRanked(final T in, final UnsignedShortType out) {
            out.setReal(inputToRanked[(int) in.getRealFloat()]);
        }

        public <T extends RealType< T >> void toRanked(final T in, final UnsignedByteType out) {
            out.setReal(inputToRanked[(int) in.getRealFloat()]);
        }

    }

}