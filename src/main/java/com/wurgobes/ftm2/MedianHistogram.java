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

    private int median;//The median of this pixel
    private int aux;   //Marks the position of the median pixel in the column of the histogram, starting with 1

    public final int[] history; //Keeps track of the last windox pixels
    private int hi; //Keeps track of the index of next pixel to overwrite


    private final int window; //amount of pixels to calculate the median of

    //Initialisation when the new method is NOT used
    public MedianHistogram(int window, int maxVal) {
        //Initialise values
        this.window = window;


        //(window - 1) / 2 (value at which the median sits)
        int windowC = (window - 1) / 2; //0 indexed sorted array has median at this position.
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

    public void add(final int pixel2) {
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