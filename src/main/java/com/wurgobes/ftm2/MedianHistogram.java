package com.wurgobes.ftm2;
/* Median Finding Algorithm
(c) 2020 Martijn Gobes, Holhbein Lab, Wageningen University
Based on the Fast Temporal Median Filter for ImageJ by the Netherlands Cancer Institute
and on the fast median implementation as proposed by Mao-Hsiung Hung et al.
This class would run on every pixel in an image and is therefore kept minimal.

This class implements the two different methods, with a switch between them.
After more testing a clear faster method might arise, and the other will be removed.
It is not a drop in replacement, they require slightly different initialisation.

This algorithm is based on:
Mao-Hsiung Hung et al. A Fast Algorithm of Temporal Median Filter for Background Subtraction
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


public class MedianHistogram {

    private final int[] hist; //Gray-level histogram init at 0
    private final boolean newMethod; //Set if the newer method is used

    private int median;//The median of this pixel
    private int aux;   //Marks the position of the median pixel in the column of the histogram, starting with 1

    public final int[] history; //Keeps track of the last windox pixels
    private int hi; //Keeps track of the index of next pixel to overwrite

    private final int maxVal; //Maximum value possible

    private final int window; //amount of pixels to calculate the median of
    private final int windowC; //(window - 1) / 2 (value at which the median sits)
    private int lb; //lower bound of the median
    private int ub; //upper bound of the median

    //Lookup tables as described in Mao-Hsiung Hung et al.
    private final int[] lookup_lb = {0, -1, -1, 1, 0,  0, 1, 0, 0};
    private final int[] lookup_ub = {0,  0, -1, 0, 0, -1, 1, 1, 0};

    //Initialisation when the new method is NOT used
    public MedianHistogram(int window, int maxVal, boolean newMethod) {
        //Initialise values
        this.window = window;
        this.maxVal = maxVal;
        this.newMethod = newMethod;


        windowC = (window - 1) / 2; //0 indexed sorted array has median at this position.
        hist = new int[maxVal + 1]; //Gray-level histogram init at 0

        // Set window values at the 0 position of the histogram
        // These will be overwritten by the first part of the algorithm in TemporalMedian.java
        // This also sets the aux  because there are 50 buffer values, and the median is 0
        hist[0] = window;
        aux = windowC + 1;
        median = 0;

        history = new int[window]; // Initialise the history array
        hi = 0;//First pixel to overwrite is the first one
    }

    public MedianHistogram(int window, int maxVal, int[] StartingValues, boolean newMethod) {
        //Initialise values
        this.maxVal = maxVal;
        this.window = window;
        this.newMethod = newMethod;


        windowC = (window - 1) / 2; //0 indexed sorted array has median at this position.
        hist = new int[maxVal + 1]; //Gray-level histogram init at 0

        // Copy the starting values into the histogram
        for (int val: StartingValues) {
            hist[val]++;
        }

        // Do the initial build
        // If we don't do this lb and ub wn't be properly set
        build_histogram();

        // Copy the starting values into the history
        history = StartingValues.clone();
        hi = 0; //First pixel to overwrite is the first one
    }

    private void build_histogram(){
        // This method is adapted from Mao-Hsiung Hung et al.

        // This is a fairly simplistic median finding algorithm
        // It iterates through all values until it finds the windowC'th value
        // lb is how many values are the buffer below
        // ub is how many values are the buffer above
        // I'm certain this is a good area for improvement
        int csum = 0;
        for(int i = 0; i < maxVal; i++){
                if(hist[i] > 0){
                csum += hist[i];
                if (csum > windowC){
                    lb = csum - hist[i] + 1;
                    ub = csum;
                    median = i;
                    break;
                }
            }
        }
    }

    public void add(final int new_pixel){
        // Selects between the two methods
        if(newMethod){
            add_new(new_pixel);
        } else {
            add_old(new_pixel);
        }
    }

    public void add_new(final int new_pixel){
        //Get the old pixel and record the new
        final int old_pixel = record(new_pixel);

        hist[old_pixel]--; //Removing old pixel
        hist[new_pixel]++; //Adding new pixel

        // Shortened method of determining where to move the lb and ub
        int c = 3 * (old_pixel < median ? 0 : old_pixel == median ? 1 : 2) + (new_pixel < median ? 0 : new_pixel == median ? 1 : 2);

        // Move the lb and ub based on c and the lookup tables.
        lb += lookup_lb[c];
        ub += lookup_ub[c];

        //If the lb/ub moved out of the window, rebuild the histogram
        if(lb <= windowC && windowC <= ub){
            build_histogram();
        }
    }

    public void add_old(final int pixel2) {
        // (c) 2019 Rolf Harkes, Netherlands Cancer Institute.
        // This method was the original and is not changed


        //Get the old pixel and record the new
        final int pixel = record(pixel2);

        hist[pixel]--; //Removing old pixel
        hist[pixel2]++; //Adding new pixel
        if (!(
                (pixel > median && pixel2 > median)
                        || (pixel < median && pixel2 < median)
                        || (pixel == median && pixel2 == median)
        )) //Add and remove the same pixel, or pixel from the same side, the median doesn't change
        {
            int j = median;
            if ((pixel2 > median) && (pixel < median)) //The median goes right
            {
                if (hist[median] == aux) //The previous median was the last pixel of its column in the histogram, so it changes
                {
                    j++;
                    while (hist[j] == 0) //Searching for the next pixel
                    {
                        j++;
                    }
                    median = j;
                    aux = 1; //The median is the first pixel of its column
                } else {
                    aux++; //The previous median wasn't the last pixel of its column, so it doesn't change, just need to mark its new position
                }
            } else if ((pixel > median) && (pixel2 < median)) //The median goes left
            {
                if (aux == 1) //The previous median was the first pixel of its column in the histogram, so it changes
                {
                    j--;
                    while (hist[j] == 0) //Searching for the next pixel
                    {
                        j--;
                    }
                    median = j;
                    aux = hist[j]; //The median is the last pixel of its column
                } else {
                    aux--; //The previous median wasn't the first pixel of its column, so it doesn't change, just need to mark its new position
                }
            } else if (pixel2 == median) //new pixel = last median
            {
                if (pixel < median) //old pixel < last median, the median goes right
                {
                    aux++; //There is at least one pixel above the last median (the one that was just added), so the median doesn't change, just need to mark its new position
                }                                //else, absolutely nothing changes
            } else //pixel==median, old pixel = last median
            {
                if (pixel2 > median) //new pixel > last median, the median goes right
                {
                    if (aux == (hist[median] + 1)) //The previous median was the last pixel of its column, so it changes
                    {
                        j++;
                        while (hist[j] == 0) //Searching for the next pixel
                        {
                            j++;
                        }
                        median = j;
                        aux = 1; //The median is the first pixel of its column
                    }
                    //else, absolutely nothing changes
                } else //pixel2<median, new pixel < last median, the median goes left
                {
                    if (aux == 1) //The previous median was the first pixel of its column in the histogram, so it changes
                    {
                        j--;
                        while (hist[j] == 0) //Searching for the next pixel
                        {
                            j--;
                        }
                        median = j;
                        aux = hist[j]; //The median is the last pixel of its column
                    } else {
                        aux--; //The previous median wasn't the first pixel of its column, so it doesn't change, just need to mark its new position
                    }
                }
            }
        }
    }

    private int record(int value) {
        final int old = history[hi]; // Get the old value
        history[hi] = value; //Overwrite the old value
        if (++hi >= window) hi = 0; //If the hi is over the window, set it back to 0
        return old;
    }

    // Get the Median
    public int get() {
        return median;
    }

}