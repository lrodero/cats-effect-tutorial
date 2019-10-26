cats-effect-tutorial
====================

Source code of the examples/exerciese of [cats-effect
tutorial](https://lrodero.github.io/cats-effect/tutorial/tutorial.html).

All contents are realeased under the [Apache v2
license](https://www.apache.org/licenses/LICENSE-2.0).

There are different releases of this tutorial for different versions
of cats-effect. Each release is properly tagged (_e.g._ `cats-effect_v2.0.0`
for cats-effect v2.0.0).

Compile and run the examples
----------------------------
Code can be compiled using `sbt`:
```bash
$ sbt
> compile
```

Any of the files can be executed also using `sbt`. So for example to run
`catsEffectTutorial.CopyFile` to copy an `origin.txt` file to another
`destination.txt` file we will run:
```bash
> runMain catsEffectTutorial.CopyFile origin.txt destination.txt
```
