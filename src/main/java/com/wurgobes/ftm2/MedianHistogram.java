package com.wurgobes.ftm2;
/* Median finding histogram 
(c) 2019 Rolf Harkes, Netherlands Cancer Institute.
Based on the Fast Temporal Median Filter for ImageJ by the Milstein Lab.
This class would run on every pixel in an image and is therefore kept minimal.
The medianFindingHistogram is created once and then updated with new values.

Known limitations for increased speed
* only odd windows where the median is the center value

Known limitations due to usage of the short datatype:
* no more than 32767 unique values allowed (a SMLM experiment would usually have about 1000 unique values)
* no more than 32767 equal values allowed  (the window is usually 501)

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


import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MedianHistogram {
    private final int window;

    private final int[] hist; //Gray-level histogram init at 0

    private int median;//The median of this pixel


    private final int[] history;
    private int hi;

    private final int maxVal;

    private final int windowC;
    private int lb;
    private int ub;

    private final int[] lookup_lb = {0, -1, -1, 1, 0,  0, 1, 0, 0};
    private final int[] lookup_ub = {0,  0, -1, 0, 0, -1, 1, 1, 0};




    public MedianHistogram(int window, int maxVal, int[] StartingValues) {
        this.window = window;
        this.maxVal = maxVal;

        //0 indexed sorted array has median at this position.
        windowC = (window - 1) / 2; //0 indexed sorted array has median at this position.
        hist = new int[maxVal + 1]; //Gray-level histogram init at 0


        for (int val: StartingValues) {
            hist[val]++;
        }

        build_histogram();

        history = StartingValues;
        hi = 0;
    }

    private void build_histogram(){
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

    public void add_new(final int new_pixel){
        final int old_pixel = record(new_pixel);

        hist[old_pixel]--; //Removing old pixel
        hist[new_pixel]++; //Adding new pixel


        int c = 3 * (old_pixel < median ? 0 : old_pixel == median ? 1 : 2) + (new_pixel < median ? 0 : new_pixel == median ? 1 : 2);

        lb += lookup_lb[c];
        ub += lookup_ub[c];

        if(lb <= windowC && windowC <= ub){
            build_histogram();
        }
    }

    private int record(int value) {
        final int old = history[hi];
        history[hi] = value;
        hi = ++hi % 50;
        return old;
    }

    /**
     * Get current median.
     * It only makes sense to call this after adding {@code windowWidth} pixels.
     */
    public int get() {
        return median;
    }


}