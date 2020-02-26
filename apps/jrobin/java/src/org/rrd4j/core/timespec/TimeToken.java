package org.rrd4j.core.timespec;

class TimeToken {
    /** Constant <code>MIDNIGHT=1</code> */
    public static final int MIDNIGHT = 1;
    /** Constant <code>NOON=2</code> */
    public static final int NOON = 2;
    /** Constant <code>TEATIME=3</code> */
    public static final int TEATIME = 3;
    /** Constant <code>PM=4</code> */
    public static final int PM = 4;
    /** Constant <code>AM=5</code> */
    public static final int AM = 5;
    /** Constant <code>YESTERDAY=6</code> */
    public static final int YESTERDAY = 6;
    /** Constant <code>TODAY=7</code> */
    public static final int TODAY = 7;
    /** Constant <code>TOMORROW=8</code> */
    public static final int TOMORROW = 8;
    /** Constant <code>NOW=9</code> */
    public static final int NOW = 9;
    /** Constant <code>START=10</code> */
    public static final int START = 10;
    /** Constant <code>END=11</code> */
    public static final int END = 11;
    /** Constant <code>SECONDS=12</code> */
    public static final int SECONDS = 12;
    /** Constant <code>MINUTES=13</code> */
    public static final int MINUTES = 13;
    /** Constant <code>HOURS=14</code> */
    public static final int HOURS = 14;
    /** Constant <code>DAYS=15</code> */
    public static final int DAYS = 15;
    /** Constant <code>WEEKS=16</code> */
    public static final int WEEKS = 16;
    /** Constant <code>MONTHS=17</code> */
    public static final int MONTHS = 17;
    /** Constant <code>YEARS=18</code> */
    public static final int YEARS = 18;
    /** Constant <code>MONTHS_MINUTES=19</code> */
    public static final int MONTHS_MINUTES = 19;
    /** Constant <code>NUMBER=20</code> */
    public static final int NUMBER = 20;
    /** Constant <code>PLUS=21</code> */
    public static final int PLUS = 21;
    /** Constant <code>MINUS=22</code> */
    public static final int MINUS = 22;
    /** Constant <code>DOT=23</code> */
    public static final int DOT = 23;
    /** Constant <code>COLON=24</code> */
    public static final int COLON = 24;
    /** Constant <code>SLASH=25</code> */
    public static final int SLASH = 25;
    /** Constant <code>ID=26</code> */
    public static final int ID = 26;
    /** Constant <code>JUNK=27</code> */
    public static final int JUNK = 27;
    /** Constant <code>JAN=28</code> */
    public static final int JAN = 28;
    /** Constant <code>FEB=29</code> */
    public static final int FEB = 29;
    /** Constant <code>MAR=30</code> */
    public static final int MAR = 30;
    /** Constant <code>APR=31</code> */
    public static final int APR = 31;
    /** Constant <code>MAY=32</code> */
    public static final int MAY = 32;
    /** Constant <code>JUN=33</code> */
    public static final int JUN = 33;
    /** Constant <code>JUL=34</code> */
    public static final int JUL = 34;
    /** Constant <code>AUG=35</code> */
    public static final int AUG = 35;
    /** Constant <code>SEP=36</code> */
    public static final int SEP = 36;
    /** Constant <code>OCT=37</code> */
    public static final int OCT = 37;
    /** Constant <code>NOV=38</code> */
    public static final int NOV = 38;
    /** Constant <code>DEC=39</code> */
    public static final int DEC = 39;
    /** Constant <code>SUN=40</code> */
    public static final int SUN = 40;
    /** Constant <code>MON=41</code> */
    public static final int MON = 41;
    /** Constant <code>TUE=42</code> */
    public static final int TUE = 42;
    /** Constant <code>WED=43</code> */
    public static final int WED = 43;
    /** Constant <code>THU=44</code> */
    public static final int THU = 44;
    /** Constant <code>FRI=45</code> */
    public static final int FRI = 45;
    /** Constant <code>SAT=46</code> */
    public static final int SAT = 46;
    /** Constant <code>EOF=-1</code> */
    public static final int EOF = -1;

    final String value; /* token name */
    final int token_id;   /* token id */

    /**
     * <p>Constructor for TimeToken.</p>
     *
     * @param value a {@link java.lang.String} object.
     * @param token_id a int.
     */
    public TimeToken(String value, int token_id) {
        this.value = value;
        this.token_id = token_id;
    }

    /**
     * <p>toString.</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String toString() {
        return value + " [" + token_id + "]";
    }
}
