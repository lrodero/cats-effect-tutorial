cats-effect-tutorial
====================

Source code of the examples/exercises of [cats-effect
tutorial](https://lrodero.github.io/cats-effect/tutorial/tutorial.html).

All contents released under the [Apache v2 license](https://www.apache.org/licenses/LICENSE-2.0).

Compile and run the examples
----------------------------
Code can be compiled using `sbt`:
```bash
$ sbt
> compile
```

Any of the files can be executed also using `sbt`. So for example to run
`catsEffectTutorial.copyfile.CopyFile` to copy an `origin.txt` file to another
`destination.txt` file we will run:
```bash
$ sbt
> runMain catsEffectTutorial.copyfile.CopyFile origin.txt destination.txt
```
