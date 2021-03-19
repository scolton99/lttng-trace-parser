#this script gives all the events where the etc/passwd file was touched
with open('result.out', 'r') as f:
    lines = [line.rstrip() for line in f]

etc_lines = []

for line in lines:
    if "etc/passwd" in line: 
        etc_lines.append(line)

with open('total_pwds.txt', 'w') as txt_file:
    for line in etc_lines:
        txt_file.write(line + '\n')