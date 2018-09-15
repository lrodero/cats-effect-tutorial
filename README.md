cats-effect-tutorial
====================

Tutorial for [cats-effect](https://typelevel.org/cats-effect) written in markdown, resulting HTML is available [online](https://lrodero.github.io/cats-effect-tutorial/tutorial.html). Also the source code of the examples int the tutorial are available in this same repo as an `sbt` project.

All contents are realeased under the [Apache v2 license](https://www.apache.org/licenses/LICENSE-2.0).

Compile and run the examples
----------------------------
Code can be compiled using `sbt`:
```bash
$ sbt
> compile
```

Any of the files can be executed also using `sbt`. So for example to run `tutorial.CopyFile` to copy an `origin.txt` file to another `destination.txt` file we will run:
```bash
> runMain catsEffectTutorial.CopyFile origin.txt destination.txt
```

Generate the tutorial HTML
--------------------------
The tutorial text is written using MarkDown, and can be found in `tutorial.md`. The HTML version in `tutorial.html` was generated using [pandoc](https://pandoc.org), with the following command:

```bash
$ pandoc tutorial.md -f markdown -t html --highlight-style tango --css pandoc.css -s -o tutorial.html
```

The CSS used for the generation, `pandoc.css`, can be downloaded from [the project page at github](https://gist.github.com/killercup/5917178).
