#this script gives all the mounts, pid's, and vpid's that are associated with events involving the etc/passwds file

with open('total_pwds.txt', 'r') as f:
    lines = [line.rstrip() for line in f]

mounts = set()
pids = set()
vpids = set()

# line = lines[0]
# x = line.split(", ")
# print(x[3])
# pid = x[3].split("= ")[1]
# print(pid)
# print(x[4])
# vpid = x[4].split("= ")[1]
# print(vpid)
# # mount = x[5]
# # x_m = mount.split('= ')
# # print(x_m[1])


for line in lines:
    x = line.split(", ")
    pid = x[3].split("= ")[1]
    vpid = x[4].split("= ")[1]
    mount = x[5].split('= ')[1]
    if pid not in pids:
        pids.add(pid)
    if vpid not in vpids:
        vpids.add(vpid)
    if mount not in mounts:
        mounts.add(mount)


pid_string = "pids: "
for x in pids: 
    pid_string += x + " "
vpid_string = "vpids: "
for x in vpids: 
    vpid_string += x + " "
mount_string = "mounts: "
for x in mounts: 
    mount_string += x + " "

with open('pwds_info.txt', 'w') as txt_file:
    txt_file.write(pid_string + '\n')
    txt_file.write(vpid_string + '\n')
    txt_file.write(mount_string)