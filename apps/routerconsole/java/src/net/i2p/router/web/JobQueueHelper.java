package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.router.Job;
import net.i2p.router.JobStats;

public class JobQueueHelper extends HelperBase {
    
    public String getJobQueueSummary() {
        try {
            if (_out != null) {
                renderStatusHTML(_out);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(32*1024);
                renderStatusHTML(new OutputStreamWriter(baos));
                return new String(baos.toByteArray());
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }

    /**
     *  Moved from JobQueue
     *  @since 0.8.9
     */
    private void renderStatusHTML(Writer out) throws IOException {
        List<Job> readyJobs = new ArrayList(8);
        List<Job> timedJobs = new ArrayList(128);
        List<Job> activeJobs = new ArrayList(8);
        List<Job> justFinishedJobs = new ArrayList(8);
        
        int numRunners = _context.jobQueue().getJobs(readyJobs, timedJobs, activeJobs, justFinishedJobs);
        
        StringBuilder buf = new StringBuilder(32*1024);
        buf.append("<b><div class=\"joblog\"><h3>I2P Job Queue</h3><div class=\"wideload\">Job runners: ").append(numRunners);
        buf.append("</b><br>\n");

        long now = _context.clock().now();

        buf.append("<hr><b>Active jobs: ").append(activeJobs.size()).append("</b><ol>\n");
        for (int i = 0; i < activeJobs.size(); i++) {
            Job j = activeJobs.get(i);
            buf.append("<li>[started ").append(DataHelper.formatDuration2(now-j.getTiming().getStartAfter())).append(" ago]: ");
            buf.append(j.toString()).append("</li>\n");
        }
        buf.append("</ol>\n");
        buf.append("<hr><b>Just finished jobs: ").append(justFinishedJobs.size()).append("</b><ol>\n");
        for (int i = 0; i < justFinishedJobs.size(); i++) {
            Job j = justFinishedJobs.get(i);
            buf.append("<li>[finished ").append(DataHelper.formatDuration2(now-j.getTiming().getActualEnd())).append(" ago]: ");
            buf.append(j.toString()).append("</li>\n");
        }
        buf.append("</ol>\n");
        buf.append("<hr><b>Ready/waiting jobs: ").append(readyJobs.size()).append("</b><ol>\n");
        for (int i = 0; i < readyJobs.size(); i++) {
            Job j = readyJobs.get(i);
            buf.append("<li>[waiting ");
            buf.append(DataHelper.formatDuration2(now-j.getTiming().getStartAfter()));
            buf.append("]: ");
            buf.append(j.toString()).append("</li>\n");
        }
        buf.append("</ol>\n");
        out.flush();

        buf.append("<hr><b>Scheduled jobs: ").append(timedJobs.size()).append("</b><ol>\n");
        TreeMap<Long, Job> ordered = new TreeMap();
        for (int i = 0; i < timedJobs.size(); i++) {
            Job j = timedJobs.get(i);
            ordered.put(Long.valueOf(j.getTiming().getStartAfter()), j);
        }
        for (Job j : ordered.values()) {
            long time = j.getTiming().getStartAfter() - now;
            buf.append("<li>").append(j.getName()).append(" in ");
            buf.append(DataHelper.formatDuration2(time)).append("</li>\n");
        }
        buf.append("</ol></div>\n");
        
        out.flush();
        
        getJobStats(buf);
        
        out.flush();
        
        out.write(buf.toString());
    }
    
    /**
     *  Render the HTML for the job stats.
     *  Moved from JobQueue
     *  @since 0.8.9
     */
    private void getJobStats(StringBuilder buf) { 
        buf.append("<table>\n" +
                   "<tr><th>Job</th><th>Runs</th>" +
                   "<th>Time</th><th><i>Avg</i></th><th><i>Max</i></th><th><i>Min</i></th>" +
                   "<th>Pending</th><th><i>Avg</i></th><th><i>Max</i></th><th><i>Min</i></th></tr>\n");
        long totRuns = 0;
        long totExecTime = 0;
        long avgExecTime = 0;
        long maxExecTime = -1;
        long minExecTime = -1;
        long totPendingTime = 0;
        long avgPendingTime = 0;
        long maxPendingTime = -1;
        long minPendingTime = -1;

        List<JobStats> tstats = new ArrayList(_context.jobQueue().getJobStats());
        Collections.sort(tstats, new JobStatsComparator());
        
        for (JobStats stats : tstats) {
            buf.append("<tr>");
            buf.append("<td><b>").append(stats.getName()).append("</b></td>");
            buf.append("<td align=\"right\">").append(stats.getRuns()).append("</td>");
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(stats.getTotalTime())).append("</td>");
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(stats.getAvgTime())).append("</td>");
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(stats.getMaxTime())).append("</td>");
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(stats.getMinTime())).append("</td>");
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(stats.getTotalPendingTime())).append("</td>");
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(stats.getAvgPendingTime())).append("</td>");
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(stats.getMaxPendingTime())).append("</td>");
            buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(stats.getMinPendingTime())).append("</td>");
            buf.append("</tr>\n");
            totRuns += stats.getRuns();
            totExecTime += stats.getTotalTime();
            if (stats.getMaxTime() > maxExecTime)
                maxExecTime = stats.getMaxTime();
            if ( (minExecTime < 0) || (minExecTime > stats.getMinTime()) )
                minExecTime = stats.getMinTime();
            totPendingTime += stats.getTotalPendingTime();
            if (stats.getMaxPendingTime() > maxPendingTime)
                maxPendingTime = stats.getMaxPendingTime();
            if ( (minPendingTime < 0) || (minPendingTime > stats.getMinPendingTime()) )
                minPendingTime = stats.getMinPendingTime();
        }

        if (totRuns != 0) {
            if (totExecTime != 0)
                avgExecTime = totExecTime / totRuns;
            if (totPendingTime != 0)
                avgPendingTime = totPendingTime / totRuns;
        }

        buf.append("<tr class=\"tablefooter\">");
        buf.append("<td><b>").append("SUMMARY").append("</b></td>");
        buf.append("<td align=\"right\">").append(totRuns).append("</td>");
        buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(totExecTime)).append("</td>");
        buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(avgExecTime)).append("</td>");
        buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(maxExecTime)).append("</td>");
        buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(minExecTime)).append("</td>");
        buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(totPendingTime)).append("</td>");
        buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(avgPendingTime)).append("</td>");
        buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(maxPendingTime)).append("</td>");
        buf.append("<td align=\"right\">").append(DataHelper.formatDuration2(minPendingTime)).append("</td>");
        buf.append("</tr></table></div>\n");
    }

    /** @since 0.8.9 */
    private static class JobStatsComparator implements Comparator<JobStats> {
         public int compare(JobStats l, JobStats r) {
             return l.getName().compareTo(r.getName());
        }
    }

}
