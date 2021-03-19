#this script finds all the events where the pid and the vpid are distinct aka all the container processes

with open('result.out', 'r') as f:
    lines = [line.rstrip() for line in f]

dif_lines = []

for line in lines:
    x = line.split(", ")
    pid = x[3]
    pid_id = pid[len(pid)-4:]
    vpid = x[4]
    vpid_id = vpid[len(vpid)-4:]
    if pid_id != vpid_id:
        dif_lines.append(line)

with open('results-filtered.txt', 'w') as txt_file:
    for line in dif_lines:
        txt_file.write(line + '\n')