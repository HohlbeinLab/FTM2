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
import java.util.Iterator;
import java.util.concurrent.atomic.DoubleAccumulator;



import net.imglib2.*;

import net.imglib2.converter.Converters;


import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedIntType;
import net.imglib2.type.numeric.integer.UnsignedShortType;


import static ij.util.ThreadUtil.createThreadArray;
import static ij.util.ThreadUtil.startAndJoin;
import static java.lang.Math.min;


public class TemporalMedian {

    @SuppressWarnings("unchecked")
    public static  < T extends RealType< T >, U extends IntegerType<U>>  void main(RandomAccessibleInterval<T> img, int window, int bit_depth, final int offset) {
		final int windowC = window / 2; //This is the Index of the median
		final int imgw = (int) img.dimension(0); // width of frame
        final int imgh = (int) img.dimension(1); // height of frame
        final int pixels = imgw * imgh; // Total amount of pixels
        final int zSize = (int) img.dimension(2);
        final int zSteps = zSize - window;


        final RandomAccessibleInterval<U> int_img = (RandomAccessibleInterval<U>) img;

        // Build the rankmap from the image and use that to convert the original image
        // This compacts the image and reduces the memory footprint required.
        // This effectively removes all zero values from the histogram
        final RankMap rankmap = RankMap.build((IterableInterval<U>) int_img);
        final RandomAccessibleInterval<U> ranked;


        switch(bit_depth){
            case 16:
                ranked = (RandomAccessibleInterval<U>) Converters.convert( int_img, rankmap::toRanked, new UnsignedShortType());
                break;
            case 8:
                ranked = (RandomAccessibleInterval<U>) Converters.convert( int_img, rankmap::toRanked, new UnsignedByteType());
                break;
            default:
                ranked = (RandomAccessibleInterval<U>) Converters.convert(int_img, rankmap::toRanked, new UnsignedIntType());
        }


        final AtomicInteger ai = new AtomicInteger(0); //Atomic Integer is a thread safe incremental integer
        final Thread[] threads = createThreadArray(); //Get maximum of threads
        //Set the run function for each thread
		for (int ithread = 0; ithread < threads.length; ithread++) {
            threads[ithread] = new Thread(() -> {

                // Get the RandomAccess, twice for the same image
                RandomAccess<U> front = ranked.randomAccess(); // front is used to read new values
                RandomAccess<U> back = (RandomAccess<U>) img.randomAccess(); // back is used to set the median corrected values
                MedianHistogram median = new MedianHistogram(window, rankmap.getMaxRank());
                for (int j = ai.getAndIncrement(); j < pixels; j = ai.getAndIncrement()) { //get unique i

                    final int[] pos = { j % imgw, j / imgw, offset }; //Get position based on j
                    front.setPosition(pos); // Set the starting position for the front
                    back.setPosition(pos); // Set the starting position for the back

                    // read the first window ranked pixels into median filter

                    for (int i = 0; i <  window; ++i) {
                        // Get the next value and add it to the median object or the startingvalues
                        median.add(front.get().getInteger());
                        front.fwd(2); // Move the front one forward in the 2nd dimension to the next slice
                    }

                    int temp_median = rankmap.fromRanked(median.get()); // The median won't change so we read it once
                    // write current median for windowC+1 pixels
                    for (int i = 0; i <  windowC; ++i) {
                        final U t = back.get(); // Get the reference to the back's pixel
                        t.setInteger(Math.max(t.getInteger() - temp_median, 0)); // Set the back's value, median adjusted
                        back.fwd(2); // Move the back one forward in the 2nd dimension to the next slice
                    }


                    for (int i = 0; i <  zSteps; ++i) {
                        median.add((int) front.get().getRealFloat());
                        front.fwd(2); // Move the front one forward in the 2nd dimension to the next slice
                        final U t = back.get(); // Get the reference to the back's pixel
                        t.setInteger(Math.max(t.getInteger() - rankmap.fromRanked( median.get()), 0)); // Set the back's value, median adjusted
                        back.fwd(2); // Move the front one forward in the 2nd dimension to the next slice
                    }

                    temp_median = rankmap.fromRanked(median.get());
                    // write current median for windowC pixels
                    for (int i = 0; i < windowC; ++i) {
                        final U t = back.get(); // Get the reference to the back's pixel
                        t.setInteger(Math.max(t.getInteger() - temp_median, 0)); // Set the back's value, median adjusted
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


        private static int maxRank; // Maximum value in the input

        final static int U32_SIZE = 4_000_000;

        // Simple Constructor for Rankmap, dont call this, but call build()
        public RankMap(final int[] inputToRanked, final int[] rankedToInput) {
            this.inputToRanked = inputToRanked;
            this.rankedToInput = rankedToInput;
        }

        // This is the real constructor
        // It is only called once per thread
        public static < U extends IntegerType<U>>  RankMap build(final IterableInterval<U> input)
        {
            // this denotes the maximum unique values
            // It will never be this high, but better be safe
            final int U8_SIZE = 256;
            final int U16_SIZE = 65536;

            final int mapSize;


            final boolean[] inihist;

            // Set mapSize to what bit-depth you have
            if (input.firstElement().getBitsPerPixel() == 8) {
                mapSize = U8_SIZE;

            } else if (input.firstElement().getBitsPerPixel() == 32) {

                // This method only supports integer values
                // 32b images can be float however
                // this creates a mapping from the original values between 0 and U32_SIZE
                // This loses image precision, but how much depends on the range of values in input
                // I recommend converting it to 32b Integers to prevent this loss

                mapSize = min((int) computeMinMax(input.iterator()) + 1, U32_SIZE + 1);

            } else {
                mapSize = U16_SIZE;
            }
            inihist= new boolean[mapSize];
            input.forEach(t -> inihist[t.getInteger()] = true);


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

        private static < U extends IntegerType<U>> double computeMinMax(Iterator<U> iterator) {
            DoubleAccumulator max = new DoubleAccumulator(Double::max, Double.NEGATIVE_INFINITY);

            iterator.forEachRemaining(t -> max.accumulate(t.getInteger()));
            return max.get();
        }

        public int fromRanked(final int in) {
            return rankedToInput[in];
        }

        public int getMaxRank() {
            return maxRank;
        }


        public <U extends IntegerType<U>> void toRanked(final U in, final UnsignedIntType out) {
            out.setInteger(inputToRanked[in.getInteger()]);
        }

        public <U extends IntegerType<U>> void toRanked(final U in, final UnsignedShortType out) {
            out.setInteger(inputToRanked[in.getInteger()]);
        }

        public <U extends IntegerType<U>> void toRanked(final U in, final UnsignedByteType out) {
            out.setInteger(inputToRanked[in.getInteger()]);
        }



    }
}