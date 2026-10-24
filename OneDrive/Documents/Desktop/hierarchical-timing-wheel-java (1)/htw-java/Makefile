# Works with a full JDK (javac). Windows users without make: see README for the plain commands.
SRC = $(shell find src tests bench -name '*.java')

all:
	mkdir -p out
	javac -Xlint:all -d out $(SRC)

check: all
	java -cp out htw.TestWheel

run: all
	java -cp out htw.Main

bench: all
	java -Xmx3g -cp out htw.Benchmark 1000000 50

clean:
	rm -rf out
