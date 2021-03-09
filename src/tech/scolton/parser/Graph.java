package tech.scolton.parser;

import javax.naming.Name;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

enum NS_TYPE { NET, MNT };

class Namespace {
    private final long nsid;
    private final NS_TYPE type;
    private String root = "";

    private static final HashMap<Namespace, Set<Thread>> threadNetMap = new HashMap<>();
    private static final HashMap<Namespace, Set<Thread>> threadMntMap = new HashMap<>();

    private Namespace(long nsid, NS_TYPE type) {
        this.nsid = nsid;
        this.type = type;

        Map<Namespace, Set<Thread>> threads = getMap(type);
    }

    private static Map<Namespace, Set<Thread>> getMap(NS_TYPE type) {
        return type == NS_TYPE.NET ? threadNetMap : threadMntMap;
    }

    public static Namespace get(long nsid, NS_TYPE type) {
        Map<Namespace, Set<Thread>> threads = getMap(type);

        for (Namespace n : threads.keySet())
            if (n.nsid == nsid && n.type == type) return n;

        return new Namespace(nsid, type);
    }

    public static void setNs(Thread caller, Namespace n) {
        Map<Namespace, Set<Thread>> threads = getMap(n.type);

        if (!threads.containsKey(n)) {
            threads.put(n, new HashSet<>(Set.of(caller)));
        } else {
            threads.get(n).add(caller);
        }
    }

    public static void switchNs(Thread caller, Namespace oldNs, Namespace newNs) {
        if (oldNs.type != newNs.type) throw new RuntimeException("Can't switch between namespace types");

        Map<Namespace, Set<Thread>> threads = getMap(oldNs.type);

        if (threads.containsKey(oldNs)) {
            threads.get(oldNs).remove(caller);
        }

        if (threads.containsKey(newNs)) {
            threads.get(newNs).add(caller);
        } else {
            threads.put(newNs, new HashSet<>(Set.of(caller)));
        }
    }

    public void chroot(String new_dir) {
        this.root = new_dir;
    }

    public String getRoot() {
        return this.root;
    }
}

class Thread {
    private final long vtid;
    private final long vpid;
    private final long pid;
    private final long tid;
    private Namespace net_ns = null;
    private Namespace mnt_ns = null;

    private String wd = "";

    private static final Set<Thread> threads = new HashSet<>();
    private static final Map<Thread, Namespace> ns_map = new HashMap<>();

    private Thread(long vtid, long vpid, long pid, long tid) {
        this.vtid = vtid;
        this.vpid = vpid;
        this.pid = pid;
        this.tid = tid;

        threads.add(this);
    }

    private void updateNs(NS_TYPE type, long namespace) {
        if ((type == NS_TYPE.NET ? this.net_ns : this.mnt_ns) == null) {
            Namespace n = Namespace.get(namespace, type);

            Namespace.setNs(this, n);

            if (type == NS_TYPE.NET) {
                this.net_ns = n;
            } else {
                this.mnt_ns = n;
            }
            return;
        }

        Namespace new_ns = Namespace.get(namespace, type);
        if (type == NS_TYPE.NET) {
            Namespace.switchNs(this, this.net_ns, new_ns);
            this.net_ns = new_ns;
        } else {
            Namespace.switchNs(this, this.mnt_ns, new_ns);
            this.mnt_ns = new_ns;
        }
    }

    public void setNetNs(long namespace) {
        updateNs(NS_TYPE.NET, namespace);
    }

    public void setMntNs(long namespace) {
        updateNs(NS_TYPE.MNT, namespace);
    }

    public static Thread get(long vtid, long vpid, long pid, long tid) {
        for (Thread t : threads) {
            if (t.getVtid() == vtid && t.getVpid() == vpid && t.getPid() == pid && t.getTid() == tid)
                return t;
        }

        return new Thread(vtid, vpid, pid, tid);
    }

    public void setWd(String s) {
        this.wd = s;
    }

    public String getWd() {
        return this.wd;
    }

    public long getVtid() {
        return this.vtid;
    }

    public long getTid() {
        return this.tid;
    }

    public long getVpid() {
        return this.vpid;
    }

    public long getPid() {
        return this.pid;
    }

    public Namespace getNetNs() {
        return this.net_ns;
    }

    public Namespace getMntNs() {
        return this.mnt_ns;
    }

    public String cwd() {
        return this.mnt_ns.getRoot() + this.wd;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Thread)) return false;

        Thread t = (Thread) o;
        return t.getVpid() == this.vpid && t.getVtid() == this.vtid && t.getTid() == this.tid && t.getPid() == this.pid;
    }

    @Override
    public int hashCode() {
        return (int)(this.vpid + this.vtid + this.tid + this.pid);
    }
}

abstract class Syscall {
    public abstract void execute(Thread caller, Map<String, String> params);

    private static final Map<String, Class<? extends Syscall>> calls = Map.of(
            "sched_process_fork", Fork.class,
            "syscall_entry_chdir", Chdir.class,
            "syscall_entry_chroot", Chroot.class,
            "syscall_entry_pivot_root", PivotRoot.class,
            "syscall_entry_openat", Open.class,
            "syscall_entry_open", Open.class
    );

    public Syscall() {};

    protected static String resolve(String p1, String p2) {
        Path path = Paths.get(p1);
        return path.resolve(Paths.get(p2)).normalize().toString();
    }

    protected static String crop(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        } else {
            return s;
        }
    }

    public static Syscall get(String s) {
        if (!calls.containsKey(s))
            return null;

        try {
            return calls.get(s).getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }

        return null;
    }
}

class Fork extends Syscall {
    @Override
    public void execute(Thread caller, Map<String, String> params) {
        long ch_pid = Long.parseLong(params.get("child_pid"));
        long ch_tid = Long.parseLong(params.get("child_tid"));
        long ch_vtid, ch_vpid;

        if (params.containsKey("_vtids_length") && Long.parseLong(params.get("_vtids_length")) > 1) {
            String vtids = params.get("vtids");
            Pattern vtid_1 = Pattern.compile("\\[1]\\s=\\s(\\d+)");
            Matcher m = vtid_1.matcher(vtids);

            if (m.find()) {
                ch_vtid = (ch_vpid = Long.parseLong(m.group(1)));
            } else {
                System.out.println(params);
                System.out.println(vtids);
                throw new RuntimeException("Can't find forked process vtid");
            }
        } else {
            ch_vpid = ch_pid;
            ch_vtid = ch_tid;
        }

        Thread nt = Thread.get(ch_vtid, ch_vpid, ch_pid, ch_tid);
        nt.setWd(caller.getWd());
    }
}

class Chdir extends Syscall {
    @Override
    public void execute(Thread caller, Map<String, String> params) {
//        System.out.println(params);
        caller.setWd(resolve(caller.getWd(), crop(params.get("filename"))));
    }
}

class Chroot extends Syscall {
    @Override
    public void execute(Thread caller, Map<String, String> params) {
        Namespace ns = caller.getMntNs();

        String oldroot = ns.getRoot();
        String cwd = caller.getWd();
        String newroot = crop(params.get("filename"));

        ns.chroot(resolve(oldroot, resolve(cwd, newroot)));
    }
}

class PivotRoot extends Syscall {
    @Override
    public void execute(Thread caller, Map<String, String> params) {
        Namespace ns = caller.getMntNs();

        String oldroot = ns.getRoot();
        String cwd = caller.getWd();
        String newroot = crop(params.get("new_root"));

        ns.chroot(resolve(oldroot, resolve(cwd, newroot)));
    }
}

class Setns extends Syscall {
    @Override
    public void execute(Thread caller, Map<String, String> params) {

    }
}

class Open extends Syscall {
    @Override
    public void execute(Thread caller, Map<String, String> params) {
        if (caller.getPid() != 68263 || caller.getVpid() != 166) return;

        System.out.println(caller.getPid() + " (" + caller.getVpid() + ") accessed " + crop(params.get("filename")) + " (" + caller.getWd() + ", " + caller.getMntNs().getRoot() + ")");
    }
}

public class Graph {
    private static final String TS = "20210221144654";
    private static final String FILENAME = "/home/scolton/result-" + TS + ".out";
    private static final String HOSTNAME = "ARECIBO-U";

    private static final Pattern id = Pattern.compile("([v]?[pt]id)\\s+=\\s+(\\d+)");
    private static final Pattern ns = Pattern.compile("((?:mn|ne)t)_ns\\s+=\\s+(\\d+)");
    private static final Pattern params = Pattern.compile("\\{[^}]*?}$");
    private static final Pattern call = Pattern.compile(HOSTNAME + "\\s(\\w+?):");
    private static final Pattern time = Pattern.compile("^\\[(.*?)]");

    private static String getSyscall(String in) {
        Matcher m = call.matcher(in);
        if (m.find())
            return m.group(1);

        return null;
    }

    private static Map<NS_TYPE, Long> getNs(String in) {
        Map<NS_TYPE, Long> res = new HashMap<>();
        Matcher m = ns.matcher(in);

        while (m.find()) {
            String type_str = m.group(1);
            NS_TYPE type = type_str.equals("mnt") ? NS_TYPE.MNT : NS_TYPE.NET;
            long value = Long.parseLong(m.group(2));
            res.put(type, value);
        }

        return res;
    }

    private static Map<String, Long> getId(String in) {
        Map<String, Long> res = new HashMap<>();
        Matcher m = id.matcher(in);

        while (m.find()) {
            String id = m.group(1);
            long value = Long.parseLong(m.group(2));
            res.put(id, value);
        }

        return res;
    }

    private static Map<String, String> getParams(String in) {
        Map<String, String> res = new HashMap<>();
        Matcher m = params.matcher(in);

        if (m.find()) {
            if (m.group().length() <= 3) return res;

            String params_str = m.group().substring(2, m.group().length() - 2);
            String[] params = params_str.split(",\\s(?!\\[)");

//            for (String s : params) {
//                System.out.println(s);
//            }

            for (String s : params) {
                String[] key_val = s.split("(?<!])\\s=\\s");
                res.put(key_val[0], key_val[1]);
            }
        }

        return res;
    }

    public static void graph() {
        File in_file = new File(FILENAME);

        try (BufferedReader in = new BufferedReader(new FileReader(in_file))) {
            String ln;
            while ((ln = in.readLine()) != null) {
                String syscall = getSyscall(ln);
                Map<String, String> params = getParams(ln);
                Map<NS_TYPE, Long> nss = getNs(ln);
                Map<String, Long> ids = getId(ln);

                long vpid = ids.get("vpid");
                long pid = ids.get("pid");
                long tid = ids.get("tid");
                long vtid = ids.get("vtid");

                Thread thread = Thread.get(vtid, vpid, pid, tid);
                thread.setMntNs(nss.get(NS_TYPE.MNT));
                thread.setNetNs(nss.get(NS_TYPE.NET));

                Syscall executor = Syscall.get(syscall);
                if (executor != null)
                    executor.execute(thread, params);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
