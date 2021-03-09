package tech.scolton.parser;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BabelParser {

    private static final Map<String, BufferedWriter> files = new HashMap<>();
    private static final String ts = "20210221152925";
    private static final String[] syscalls = {"chdir", "chroot", "pivot_root", "setns", "fork", "execve", "exec", "mkdir", "umount", "mount", "connect", "read", "write", "open", "close", "accept", "socket", "bind", "listen"};


    private static String matches(String syscall) {
        for (String call : syscalls)
            if (syscall.contains(call)) return call;

        return null;
    }

    private static BufferedWriter getWriter(String s) {
        if (files.containsKey(s)) {
            return files.get(s);
        } else {
            try {
                BufferedWriter f = new BufferedWriter(new FileWriter("/home/scolton/lxc-exploit-result/filtered-" + ts + "-" + s + ".out"));
                files.put(s, f);
                return f;
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    private static void write_all() {
        for (String s : files.keySet()) {
            try {
                files.get(s).flush();
                files.get(s).close();
            } catch (IOException e) {
                System.out.println("Failed to flush " + s);
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        String in_file = "/home/scolton/result-" + ts + ".out";

        try (BufferedReader in = new BufferedReader(new FileReader(in_file))) {
            Pattern ctx_pat = Pattern.compile("([v]?pid|mnt_ns|net_ns)\\s*=\\s*(\\d+)");
            Pattern syscall_pat = Pattern.compile("ARECIBO-U\\s(.*):");

            String ln;
            while ((ln = in.readLine()) != null) {
                Matcher ctx_match = ctx_pat.matcher(ln);
                Matcher syscall_match = syscall_pat.matcher(ln);

                Map<String, String> attrs = new HashMap<>();

                while (ctx_match.find())
                    attrs.put(ctx_match.group(1), ctx_match.group(2));

                if (syscall_match.find())
                    attrs.put("syscall", syscall_match.group(1));

                String syscall = attrs.get("syscall");
                String normalized_call;
                if (syscall != null && ((normalized_call = matches(syscall)) != null)) {
                    BufferedWriter w = getWriter(normalized_call);
                    if (w == null)
                        System.out.println("ERROR: couldn't get file handle for " + syscall + "/" + normalized_call);
                    else
                        w.write(ln + '\n');
                }
            }

            write_all();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
