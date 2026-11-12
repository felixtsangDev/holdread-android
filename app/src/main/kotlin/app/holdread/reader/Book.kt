package app.holdread.reader

data class Block(val text: String, val heading: Boolean = false)

data class Chapter(val title: String, val blocks: List<Block>)

data class Book(val title: String, val author: String, val chapters: List<Chapter>)

/** A built-in book until EPUB/TXT import lands: how-to in Cantonese, then the opening of Alice (public domain). */
object SampleBook {
    val book = Book(
        title = "試讀",
        author = "撳住讀",
        chapters = listOf(
            Chapter(
                "點樣讀",
                listOf(
                    Block("點樣讀", heading = true),
                    Block("讀緊嗰行永遠喺螢幕中間。手指喺底下條軌道由左至右慢慢 slide，軌道上面每一格就係中間嗰行嘅一隻字。"),
                    Block("手指經過一隻字，嗰隻字先算讀咗。Slide 得太快，cursor 唔會追上嚟；超前太多，就會記一次跳字。"),
                    Block("一行讀完，手指拉返去最左，再繼續下一行。想重讀，就向左 slide，但只限同一行。"),
                    Block("讀完呢章，提起手指，就會去下一章。"),
                ),
            ),
            Chapter(
                "Down the Rabbit-Hole",
                listOf(
                    Block("Down the Rabbit-Hole", heading = true),
                    Block(
                        "Alice was beginning to get very tired of sitting by her sister on the bank, and of having " +
                            "nothing to do: once or twice she had peeped into the book her sister was reading, but it had " +
                            "no pictures or conversations in it, “and what is the use of a book,” thought Alice “without " +
                            "pictures or conversations?”",
                    ),
                    Block(
                        "So she was considering in her own mind (as well as she could, for the hot day made her feel " +
                            "very sleepy and stupid), whether the pleasure of making a daisy-chain would be worth the " +
                            "trouble of getting up and picking the daisies, when suddenly a White Rabbit with pink eyes " +
                            "ran close by her.",
                    ),
                    Block(
                        "There was nothing so very remarkable in that; nor did Alice think it so very much out of the " +
                            "way to hear the Rabbit say to itself, “Oh dear! Oh dear! I shall be late!” (when she thought " +
                            "it over afterwards, it occurred to her that she ought to have wondered at this, but at the " +
                            "time it all seemed quite natural); but when the Rabbit actually took a watch out of its " +
                            "waistcoat-pocket, and looked at it, and then hurried on, Alice started to her feet, for it " +
                            "flashed across her mind that she had never before seen a rabbit with either a " +
                            "waistcoat-pocket, or a watch to take out of it, and burning with curiosity, she ran across " +
                            "the field after it, and fortunately was just in time to see it pop down a large rabbit-hole " +
                            "under the hedge.",
                    ),
                ),
            ),
        ),
    )
}
