package app.dictate;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Появилась сеть — распознать записи, которые ждут (Pending). Работа в своём потоке: onStartJob — на главном */
public class PendingJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean ok = false;
            try { ok = Pending.processAll(this); } catch (Throwable t) { Log.e("pending job: " + t); }
            jobFinished(params, !ok && Pending.count(this) > 0);
        }, "dictate-pending").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return Pending.count(this) > 0; }
}
