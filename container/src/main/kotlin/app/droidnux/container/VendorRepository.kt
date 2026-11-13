package app.droidnux.container

/**
 * An app vendor's apt repository, set up the first time one of its packages is installed: its
 * key (unarmored, [key] in base64) in [keyring] and its [source] lines in [list], both where the
 * vendor's own instructions put them, so a package that writes them too agrees with ours.
 */
internal class VendorRepository(private val keyring: String, private val list: String, private val source: List<String>, private val key: String) {

    /** Guest shell lines adding the repository when it is not there yet, and reading its package list. */
    fun setupScript(): String = listOf(
        "if [ ! -s /etc/apt/sources.list.d/$list ]; then",
        "    apt-get install -y --no-install-recommends ca-certificates",
        "    mkdir -p ${keyring.substringBeforeLast('/')}",
        "    echo '$key' | base64 -d > $keyring",
        "    printf '%s\\n' ${source.joinToString(" ") { "'$it'" }} > /etc/apt/sources.list.d/$list",
        // Only this repository's list: the others stay as they are.
        "    apt-get update -o Dir::Etc::sourcelist=sources.list.d/$list -o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0",
        "fi",
    ).joinToString("\n")

    companion object {
        // Keys checked against the vendors' published fingerprints.

        /** Microsoft's release key, BC52 8686 B50D 79E3 39D3 721C EB3E 94AD BE12 29CF: VS Code's postinst then keeps our source. */
        private val MICROSOFT = VendorRepository(
            "/usr/share/keyrings/microsoft.gpg", "vscode.sources",
            listOf("Types: deb", "URIs: https://packages.microsoft.com/repos/code", "Suites: stable", "Components: main", "Architectures: arm64", "Signed-By: /usr/share/keyrings/microsoft.gpg"),
            "mQENBFYxWIwBCADAKoZhZlJxGNGWzqV+1OG1xiQeoowKhssGAKvd+buXCGISZJwTLXZqIcIiLP7pqdcZWtE9bSc7yBY2MalDp9Liu0Kek" +
                "ywQ6VVX1T72NPf5Ev6x6DLV7aVWsCzUAF+eb7DC9fPuFLEdxmOEYoPjzrQ7cCnSV4JQxAqhU4T6OjbvRazGl3agOeizPXmRljMtUUttHQZnRh" +
                "tlzkmwIrUivbfFPD+fEoHJ1+uIdfOzZX8/oKHKLe2jH632kvsNzJFlROVvGLYAk2WRcLu+RjjggixhwiB+Mu/A8Tf4V6b+YppS44q8EvVrM+Qv" +
                "Y7LNSOffSO6Slsy9oisGTdfE39nC7pVRABEBAAG0N01pY3Jvc29mdCAoUmVsZWFzZSBzaWduaW5nKSA8Z3Bnc2VjdXJpdHlAbWljcm9zb2Z0" +
                "LmNvbT6JATQEEwEIAB4FAlYxWIwCGwMGCwkIBwMCAxUIAwMWAgECHgECF4AACgkQ6z6Urb4SKc+P9gf/diY2900wvWEgV7iMgrtGzx79W/Pb" +
                "wWiOkKoD9sdzhARXWiP8Q5teL/t5TUH6TZ3BENboDjwr705jLLPwuEDtPI9jz4kvdT86JwwG6N8gnWM8Ldi56SdJEtXrzwtlB/Fe6tyfMT1E" +
                "/PrJfgALUG9MWTIJkc0GhRJoyPpGZ6YWSLGXnk4c0HltYKDFR7q4wtI84cBu4mjZHZbxIO6r8Cci+xxuJkpOTIpr4pdpQKpECM6x5SaT2gVn" +
                "scbN0PE19KK9nPsBxyK4wW0AvAhed2qldBPTipgzPhqB2gu0jSryil95bKrSmlYJd1Y1XfNHno5Dxfn5JwgySBIdWWvtOI05gw==",
        )

        /** Mozilla's, 35BA A0B3 3E9E B396 F59C A838 C0BA 5CE6 DC63 15A3: Firefox's newest version. */
        private val MOZILLA = VendorRepository(
            "/etc/apt/keyrings/packages.mozilla.org.gpg", "mozilla.sources",
            listOf("Types: deb", "URIs: https://packages.mozilla.org/apt", "Suites: mozilla", "Components: main", "Signed-By: /etc/apt/keyrings/packages.mozilla.org.gpg"),
            "xsBNBGCRt7MBCADkYJHHQQoL6tKrW/LbmfR9ljz7ib2aWno4JO3VKQvLwjyUMPpq/SXXMOnx8jXwgWizpPxQYDRJ0SQXS9ULJ1hXRL/OgMnZ"
                + "AYvYDeV2jBnKsAIEdiG/e1qm8P4W9qpWJc+hNq7FOT13RzGWRx57SdLWSXo0KeY38r9lvjjOmT/cuOcmjwlDT9XYf/RSO+yJ/AsyMdAr+ZbD"
                + "eQUd9HYJiPdI04lGaGM02MjDMnx+monc+y54t+Z+ry1WtQdzoQt9dHlIPlV1tR+xV5DHHsejCZxu9TWzzSlL5wfBBeEz7R/OIzivGJpWQdJz"
                + "d+2QDXSRg9q2XYWP5ZVtSgjVVJjNlb6ZABEBAAHNVEFydGlmYWN0IFJlZ2lzdHJ5IFJlcG9zaXRvcnkgU2lnbmVyIDxhcnRpZmFjdC1yZWdp"
                + "c3RyeS1yZXBvc2l0b3J5LXNpZ25lckBnb29nbGUuY29tPsLAjgQTAQoAOBYhBDW6oLM+nrOW9ZyoOMC6XObcYxWjBQJgkbezAhsDBQsJCAcC"
                + "BhUKCQgLAgQWAgMBAh4BAheAAAoJEMC6XObcYxWj+igIAMFh6DrAYMeq9sbZ1ZG6oAMrinUheGQbEqe76nIDQNsZnhDwZ2wWqgVC7DgOMqlh"
                + "QmOmzm7M6Nzmq2dvPwq3xC2OeI9fQyzjT72deBTzLP7PJok9PJFOMdLfILSsUnmMsheQt4DUO0jYAX2KUuWOIXXJaZ319QyoRNBPYa5qz7qX"
                + "S7wHLOY89IDqfHt6Aud8ER5zhyOyhytcYMeaGC1g1IKWmgewnhEq02FantMJGlmmFi2eA0EPD02GC3742QGqRxLwjWsm5/TpyuU24EYKRGCR"
                + "m7QdVIo3ugFSetKrn0byOxWGBvtu4fH8XWvZkRT+u+yzH1s5yFYBqc2JTrrJvRU=",
        )

        /** Brave's, DBF1 A116 C220 B8C7 164F 9823 0686 B784 2003 8257 and the two after it. */
        private val BRAVE = VendorRepository(
            "/usr/share/keyrings/brave-browser-archive-keyring.gpg", "brave-browser-release.sources",
            listOf("Types: deb", "URIs: https://brave-browser-apt-release.s3.brave.com", "Suites: stable", "Components: main", "Signed-By: /usr/share/keyrings/brave-browser-archive-keyring.gpg"),
            "mQINBGOrCckBEADNcJUEATinOnryWPrc9nC3CrU6sDuJArVki6KMx3lkfSSATFfNreVv+OcrLtGD1E+jwSlYVX0c+7LhuuMLeJuORD9JqXNS"
                + "dbalnhEOijmzZ/Efgc3uHWP3Vp2Ljdrp8YVGLDJwbUwSQv3xEfn5UioSA3FhuOigrpmLTPSTR1TrjT81jQC2e6ychAkb0u225yjXuY/fJqx8"
                + "KV6Qoh17dudt87VAuSatK0Fo93wECfFL9NRs4+fe4Ddiw5HqOyC6wjqaEnfpDptCKj1M+tptR3BdJhnXPl/uxaNQ9ECpEVMGPJ4EX7plH7Re"
                + "YmWFg2JsRDhtJ0A5jhQ32OUAyOAlHZIvy3X2B2MLSbAz1CSVLCnB4M6q88XjfkbIvb9dKy6zcv8Tjg6GXH/sYR0/N6HtX5qhqbEAzq3QSvGw"
                + "wsQAWOgAfw9BDxPG3xR8RBpwH5gOXsG8l2519uHlxn1uPkAoK67I/+iHGLS4m4lIQKkgq9zELhf8DxXfAQJc0c9OU5eVU8tRIX5YU3bGC8cP"
                + "v7DxFBJLKFNedl5MJTjQnchH0xuJ05Jvrmi7CbL5wTX4OmecUU11iWubTVCQcbDgnmpv+hTVc7UNzRAmDGj3TDXxe7oVGkX6iKt5JJqGPW0T"
                + "O6Ge5gJ9BG4IQgFW5kiZvGQyUjRfLQOF/8l3+6+8rC7IloLhaQARAQABtElCcmF2ZSBMaW51eCBSZWxlYXNlIChCcmF2ZSBMaW51eCBSZWxl"
                + "YXNlKSA8YnJhdmUtbGludXgtcmVsZWFzZUBicmF2ZS5jb20+iQJ9BBMBCABnAhsDBQkSzAMABQsJCAcCAiICBhUKCQgLAgQWAgMBAh4HAheA"
                + "FiEE2/GhFsIguMcWT5gjBoa3hCADglcFAmdkXH0lFIAAAAAADwANbGFiZWxAYnJhdmUuY29tbGludXhfcmVsZWFzZQAKCRAGhreEIAOCV7Tc"
                + "D/9AiF5eQtqjaoZ3Pwzof2UqyiK9cm683c8zncpCZb7sKFewHtBk0i8Ufdc4jrqjHQF5G6DiSdxTqKuAtwzaxE+bl77NcA8cQO6PioIcREjU"
                + "WSYKGZ4t1fH9B5Ba45tQI9AgL7idPhRSQCqQmcrTgQKRiMcqD1sySd2zNfPZKJKElR9GDS2ypuzdYeeAXCtNm5PImvFDrbkk1Fv0qNJBLAgt"
                + "Sda9MJytAD+KaHQ3/1DpLQAVOTsDKBvtowdx2m+H6+6ciYO8VwdT8GGMfWFRAs6Ix/uC7jO2W+k/53vu5TUm7OyxkXQBsn8b+X70jlLtOAdS"
                + "Ppwb2ML9aoEE6AyqTojqiaM9u2FDNsZP9gYow9P3Owq3tQNBYUnNRhk3A7lyWWnrkjU9icN73SYJNQcAHJg0GOXG7a2WG0fcgTDwBApjrBE7"
                + "5Fh9ltM/7lgEL74y0Lnj614sV9Oet8oew7pXMcE2TdUwm6lKU0SQywamvQpHPngikYD9QBoPcqsLOXJUF0jlcK4NlrzVeSqqVXGWtGhpU5jT"
                + "mp3jlco+PlY3ck99ZnklLOoNZIDTyGxzzitu7vrE5drM0Tn2FFTDQ9wG6QuQ7oDk3qT8hSyDu8SF2O9VOrf1SPXocdmwtbPfL9dFQ4wQ/IrJ"
                + "IRh7xViYodDvGL8V7ROrWTR5NF1ZavfmM5fGnJkCDQRn2F1zARAA6GuU7+wxbehuDoa0WQjJxPJxkg3s9VVu9UIeThJknVRwCyJpgd4QNx2Y"
                + "PjDk88upQQ9t/iXAoOKaQJGIHjdPAJkiUH7K0vVa5qI4n7PU6Y0NtO4qBxKkB9v+blNP/MRc/6PE3xTM+ycI74lJiLXF89TMmwIC7kQD5o85"
                + "WWDVYyfF2k9hQc68YnVfWK/81H8xUn1+NxEYV8eoVKwQrJf5gFEPG3Dgiz1+qV9wP9mbXVCMU5WAYHOOP1phmIoSO4NI0rxYS5QORKNTSEAc"
                + "nTuqWLOjZ0XuWEZmQhdvYzwv//G4BEArJ8nw8v7mQqH5E8iA/TEUsVJ3CHiwNdslLsRr4KA+pTOHs3QW0EafdWJ4MxlEBZxFzCmR3jgJFiMj"
                + "qx5M5cgfVLRJyO7QCTOz7V50lrV0/Xt48VC5ATj7ZtSxQmSo5RvVCVk4NVpKUk6PiZVc2XdR5KNzegFY94rwrKC++mC/4NFHljTTRdh3hapW"
                + "gb58ZgLsSR5Kew0snQz77Ld/xajtdvFeYrKzlvs/KSPe0qRSQJr6Dx2AMDNwuqiXbw0ta0ZExYiBISptF9inrZzi73W394wvYINmee1dd6+2"
                + "i1hmSYc+GT7rkF/f3pGKmcWKdKsm+4o69is0l8sCVitIpUZuDoBCcfhx6jveVxSQz4CHA/r6MY3GMQm8FuBZHDcAEQEAAbRDQnJhdmUgTGlu"
                + "dXggUmVsZWFzZSAoQnJhdmUgTGludXggUmVsZWFzZSkgPGxpbnV4LXJlbGVhc2VAYnJhdmUuY29tPokCgQQTAQgAawIbAwUJEswDAAULCQgH"
                + "AgIiAgYVCgkICwIEFgIDAQIeBwIXgBYhBEfTKnTpqeATpLSSbGjVE9Nqc82WBQJn2F12KRSAAAAAAA8AEWxhYmVsQGJyYXZlLmNvbWxpbnV4"
                + "X3JlbGVhc2VfbXJrAAoJEGjVE9Nqc82WF84QAI16IQc77DbVQPaJRebUW0JE4vdG23rdtS0voMODlhrBXzYLNWXtS9YfNVzxa2A1y+oKTGZn"
                + "0gCtj23YHACbyaL8VkfxV72UIY943ts63eyXxsCCP9YPJjuVhYLHBj/mWnnXQjEjt83jqHZvRCB6UffAaWmss5ena8MDKxOa7w5mvk36Kr0F"
                + "Hdi5KEqq9LU/y2kcNdJv+4Hg6zMJllfLYDJWhxDwWYej/kzbQuZAJu97qlQ8yHSgul+Ei+ofJkAfB2OGbBl+ZYoIqG4G9rpzEX6mD3lC8C9i"
                + "5gO/ufpOxmaXQ8/+nLnFHrBwVPVIP+6BIlahOcZxqwPhR+JQbctEz7FS0/flJG7+6IFl5AeHlKR+OYb2Z0fA5sOPhWXLtqQtFPwkGMsaZm+l"
                + "3eg9wl30xDcsdq2plA7mc5f23KEknHZtzKbN0AIxnzonwc0s5tnmIvELcU0jD3mh991I88hFL41ZrMhilQO27wPV8G0uhsCKRkCPC7NNgMLP"
                + "Q9NTAekMVzLoaZTod7oBpR557x9kUutybA/GWxlm8fG4dX0AoBvpwTXIpOLWnGaQJEcM2dhtU0eN95JSdLN6v+oRg6oZrf+r1sVwDn6xHgb2"
                + "8xZo8Cb5X6tn37WoOHrj4iNakG1UamLiIHw8udlsJiAN9l1C8BzO3XsjSq6fEb19Xh8spaHLmQINBGiIh7sBEADZtItMp3ECke3wanwQVDqM"
                + "7MIiLHm5lXSze7V2RpklOjP04OBBTKbesDK2651JR4w5eqta80BjhnU5Jn2gAsvS7yie8EXc30Th+gYAsaGdsJMIlqnQD9RRbelcJ3GEqx39"
                + "bimc3Wy8OluC21O6UQ42PEgzd5FfNv2tYHn068WJYoElCjcCHO5JLTmwBKpK4akd2+q7+GhS6foQ2SfcBFnEy4J4GuMh17cIlNE+yyKpE2eQ"
                + "2PAV/XK1LxKybvhGKuRRgIQo29Yo+ZNIe6ThS5XSitrgAk+OBhxqMJL0aSV9m0UONmwptpxIgoJeVHDJ69+Vmo5f5hq9XkoHjF6EoOQj+nih"
                + "tKdmnrvgaVNtBk4R5F85/gjYfUyeCVpntd4/N5zsiw4ZEN4iKSSvdGeTcoVolB4JA5rmcAfS5sICguColo/JJyEbjwu1WwYT3OXTG0fgzdEB"
                + "IddmNhPX/as40vt1vLkXXO9d6D65AG9VbEineZ9DKc7bIpI1rUzr6KXiIA0eb2Y2oOSyVKo8isINPsIcWpGE0nhJQM3M5xhbq7LXqP80NJyg"
                + "TrWPF5wHcyzf5Gw6Ptfm7mYiSvc3VBbXP0zVpZQZgrfgylkH2uelDBw9BcoQM6oZN85hfOu1+5Lfr9+r+xacd8auIK62CR7P64rBh93vHUln"
                + "ZVK0QpfEY7eN/QARAQABtENCcmF2ZSBMaW51eCBSZWxlYXNlIChCcmF2ZSBMaW51eCBSZWxlYXNlKSA8bGludXgtcmVsZWFzZUBicmF2ZS5j"
                + "b20+iQKABBMBCABqAhsDBQkSzAMABQsJCAcCAiICBhUKCQgLAgQWAgMBAh4HAheAFiEEsqPco1DmclZ0DfkE3k7Ge+Sw3KAFAmiIh8AoFIAA"
                + "AAAADwAQbGFiZWxAYnJhdmUuY29tbGludXhfcmVsZWFzZV92MgAKCRDeTsZ75LDcoN48D/47TkfnxqmyQ/czOK+lXfMpg8y2hZ/tooYlWAeT"
                + "uSvx+rwMAp5iUL0wXxAGynjEU2OBA8KAtdD/ALZSk87R9e8d8d0JMnXuZP0l9zOfyvaCS1CqkFWtFV72oSsFet+hJYdGHAnLruMD9AgqHAE9"
                + "ytm8rm48uppyXY4hhVJqQGWjGbd8VQLU4AM1mzojK3M/xVCDXgJ5WN5cyrb8A8nxpdaU0U7zuYhmqrmfg+bAhghq07q+T1sF+kNJr/sKRLUo"
                + "mWr7Q8TYmuWgoLY8CiTu6uKcTEfDaopdZvWL82Qn1eoiGAAb73Zztt9ckKtsUM+Ph2tJ0HBEKjK06TbjEoOd+LsD9jp9mQ27pSLIIGZ+r3I9"
                + "AMg9WRjclHlUT97OqwVu+2LRu5t/jvYJeUOmBH0fq9Co6VeNoHAWnkt1xpBhKWvamhUWTuAGdjWhhs+pcEIBpySuxtwCpAvmrPEylygBnng9"
                + "5pj43GKI1yAkPpsB9YwnZwEfQ4rTXp6Sm8FaSINrnO3JRt9vbpbUSYAj3VCGEkdfb1p8zt+ARVB7UJ90/ZnhPBdHPMa2aeOI4gMqFIIKVNCc"
                + "69r5IevM7eHd2pc2N01eR3JsWEkZLQXS+PTCmP/34rpcF45wsidbrmdktfo7KacwszjHBfKr4us/8epykbRQuVgamiiUQxXFTKl9as07JA==",
        )

        /** Sublime HQ's, 1EDD E2CD FC02 5D17 F6DA 9EC0 ADAE 6AD2 8A8F 901A and its older ones. */
        private val SUBLIME = VendorRepository(
            "/etc/apt/keyrings/sublimehq-pub.gpg", "sublime-text.sources",
            listOf("Types: deb", "URIs: https://download.sublimetext.com", "Suites: apt/stable/", "Signed-By: /etc/apt/keyrings/sublimehq-pub.gpg"),
            "mQINBFkQtZkBEADKbOf66dGnmDHnV/XEJwZUcNkn9X+bsOsbWtGqTh4ura5tEozOEBDw2eCFFFN0PlLyj79WQOscgxUyi4h5AmInYJlL6DK8"
                + "rHp9Cu0/IDtYwuO4nbUN0SMTEb/9UdyVO8to63S+2PyFre8ijh/fGPbBgtu47rEI1tNCDkreUKSQ3XpbVEQL8601tbakSoeVEApOMv06pQMc"
                + "4ewG1Qo9ogYaqvlEQFVboW6CXBr+CoP1s7pcxr0l/iJT90dMGQevFpyVt64CfQnLAmd1VOp7JfNYOTThAK/y+Da6XTp+R1kfcX7Ha1nWhGiu"
                + "OHWh7kUNQoc643Mk3M0O+TA+gamnFw/ZLYDvm2MyyTUvVdmS2Is9xllfwuqWELy3yADmSCPRcjlFU/Rsc6454HYEVd9tdaXt3LiY2WyaMp/5"
                + "mBLOXbq6I8pPPouYhWS3QSGG4HEMtiSibcXjwEzXf2cfBX1ckLL6mlaAQC1ZXs5HvnOhJT+LcbRJEe2I2J1gEAjTu7drtKIIgtYX+woNI4ju"
                + "YUfrjkJC4pQfKcS/qAdY1SuzczT8T+QSzwm25mq1KcPK6/o5QXdfUpRArH7MQBEXWeKUw0tpv3MXVrsK+WMhZLNbVYnXFNltdZvo0OPt+w/P"
                + "WR7OcWYn0lM6+zPE1t4NwmjVTh3JM2gGWf4gCtEGZpyNwW0ArQARAQABtCxTdWJsaW1lIEhRIFB0eSBMdGQgPHN1cHBvcnRAc3VibGltZXRl"
                + "eHQuY29tPokCNAQTAQIAHgUCWRC1mQIbLwMLCQcEFQoJCAUWAgMBAAIeAQIXgAAKCRCtrmrSio+QGvPyD/9bGTuBAeS/NR68txC39koiGdWp"
                + "RXvvOTDTo5tF78CLqmDb7KNjDpgwlfKriV+qdsUhvEZsA7WeB87KOqptztR8zhPWCN53hupoBsBLjvDET/AQYZYBwuCwsv90Sd8ErIK+kXxH"
                + "1XnCSIiV9AwAPPfpZDM2lv22KoxxDowzz8i+eIayZH9oaOFAoLNcaMhZywiDCH8lk5h22Jubq2ElwDAixowxdDL6xzYjTmsW6VPThdvixL4p"
                + "+/kgXWGWEPqMZUrLZvlCwGAHFdcg8o4vWibT/j7JAF0rYsOOBEzLOP+wbk6FCjwOgk8kwUaJQUuxEJp/xWw/aHcpVz48dWdXvgE+AQUY/qKe"
                + "2t2MkSPTgScjXVsATb3fZo2YBrMm2nY2OLRuXUIbCnh0ZxSKRI7+4jUPPCJPGh6xBNxnUcNal4dkeUEmZ+KL0G/4BtSIpVq/sbBnxO/FOKEh"
                + "s0z7ONrUD2KAhGRrSEgRsTCpzsvo8IdRzYnTnDAZU7ouK5oF3jQVt/dvDp9CKUfG1QoP5FSKjZIpDyxT1sOqWWjEbcPbzMnMVcVqWh+zruGT"
                + "6R8ohSduhMcFTtrQHd+ECe0tBd2DGHEmPy5lA97gLVo4y19/IIlLJxXcXJbOPkCCeHlnHq6nD/SCtnch7pDS6kaBe4VaeT8m4/EHjROAmI2S"
                + "prI1TdzS/LkCDQRZELWZARAAvlA9fJFa29VdBYDBAwygPaIfCelSwkq5UaPy9wLI0bSu5HaCnHD6FnENB2TOC0No2MXIfxQwJ4nyna37xsaL"
                + "YQO8Qt+3EJ0mFmnToyhL8tebdsSBkqprCVixAf2PjtkXtr4XxHR4L2nt9nsb5w3eCkcZ3czkafkePSsMuu8c2y6e8k+Kb+caTENWNxob/oOm"
                + "p0ybJDBKVa8JV5BVbvUd8JcWsLzKx0BSTxTH0j9eCpfBLilZZml1A9v7AgW5tK6HVNOufkR1DsHrAIQJQdyt8HKUXY0/7m3Tm7/61ONjKbuF"
                + "aIJYrkNMgr0P7BKNFMAjyJBFwa2Vf60idfxShu8svzvYBWSRWplEBnwlxSJvdQT2E6p08kOdgVX3FY4k9JgmMllE1ZsyIdF8hcpfReZn+3Rc"
                + "PrutvMYL5Cyc63xuiRUjaMLQroZ7CfFuvDRYqgkGMQbHNhWrQzHx12FQ/Mlw7mS9ypbnFhJUP4SrYKIyVW2dEUTaUpaUlfflNNRZiVZXgtnP"
                + "iIU8kRu8WjDUWKHk3QMs4KuRiudk4ZSHP+neBe9Bm67BPhVkYcpBAyJBkLTZAWmosdDCMIAyXbupR760oQUQST7tEilplvHYX8XpDBmSIM43"
                + "aMKQizI+A0HYf2zdjiz5K5hAL0lQx9HgRSbgz6vWN6FK0pEFrm3TifvdZvUAEQEAAYkEPgQYAQIACQUCWRC1mQIbAgIpCRCtrmrSio+QGsFd"
                + "IAQZAQIABgUCWRC1mQAKCRD1fU9ZvT30VPOLEACB+F2hb45D5ofEoVHgYBrD2BtPSItSAMQtvncwViH42CatT1g2n7MHwdnLts8xSCeAaEdW"
                + "zpIaMbUVO7qSkWP1gYjbq0gozEIYplzdcSFLvnDfkvSYCelJqv7GJWJxJQ59hC7V1QWKUQFf4CH8X1Mm1tHyuSe8yTBerZXWExuLE/lkBcc/"
                + "S6tSFUteODIwPeXzOMkWqf0Z8XFNNUDwlKVDcT9apvpDxE5pyOmBgJ+QRE+QbstE//nZQaDN44d2+I/4N3NJcWNIq4D0viENwJHbCvDIeeAO"
                + "ux8QEjBOWlBxYsfYwd9xecRR6IiNMnLd7zw2B1/44vbSUOTg8pVh9qJzYzolBlJSQU8cyejCoYmRt9GbWWrhoRudtKDq+5VFIoJOSAAzgNAU"
                + "gJWIRS6h+4jUoYLa9ew1eytGLTLrYR5fFVwA42WIjDfVXpP93IVSjOFswyD/YeyGjQb22xlBvVGrLv/V3bK3ghQjAqlXRItLSH4bvFRolna1"
                + "tdlpuCNdHRwdpujd0IGvG7jMmKTbNQmjIM0ZLRYXKzSOoRx90Mc8u75qvC1seMtdTSdZap2etz38Cm7kRPjqVpLbv1obUtPIVPVjxuQpY1m8"
                + "jmIT678t8W37zxCxrJ/4sOJ3PMNjnl7Lj0Y4HZsopZN2Z4Yf+EnyNwDHTaVWpWqMrkgQMz79WG3gD/oCQ3SC7/5ByapZBwahFuaS52Qmw+70"
                + "ahNkWiUNfLUZk3TTQbKpyJNziKdVW10llEr76MEw1TwdjXVPH8uEGYTtI/gcbbGTeXMWfBorgSkSdjgN6QgMgZbHpY4ljDMPdPLuivJ2+TYK"
                + "N3JCWPGorOolzezyU+yZkz6353UoX6LMGLQObB7AugQLcJO6aZnMPNqC7wof7VjWZytGvwA0id7Siudviw1IUOxj14oevNheNHwicVTTlS0f"
                + "nc+88d1AAL0jmmKyrvdawPPmuHCOosQ/ymXanAqNx/XUelGvJKSHC8i3itiVbDcArkaVrwoE2y2t//0AvStsmvKMR7UE+R0u7C9/lbU2mAam"
                + "bMJkc1XzdjEbuwD55JMqik4AXgAqARnZceV/YkLzqJG7TbHv9QSv7t1Fg16gUW3LLIfRan+sRgF3QxnmJD9xNrz0EIvIRSKhFH+EX/cQAA/y"
                + "Uw78H0YeKGlPpXsOOHp8l/ZLXsCCK2RvMFmOWfCcoBEzuiNn5I+2MUoiXkSkdsBy1F1O2ZyGTA3bhdRUW3ouD8PShapJrx8LnrM5ADzlbDvT"
                + "S0TLegNN4An5bSbj09dIRet0lkql+RTCtyWh95sr1kgGyyQCyF/Jv7NSntcQlJL3whphCpOkvOvK+HlBoY5UMcvDuGKIXk111Z3nrF4DeIIc"
                + "/U6ICZkCDQRZELDwARAApwQwwtXdXuodwh9FTYyf4LRMSE4Jc04VbzwAwhQThacFCoKp35hI+kcf3IrV90DtTAMMgAgj+1LBWneyQzaQVh0Q"
                + "RhhmRUBNRGDr13HFjeHajMh9ddOvhrcz7jEcaPqhKVsxmyBhw2R/RlYwjNEMpB2UGsCUoT3Z7IKWLg7RjJbzUCi5O3kjRrB2fzcR3mGwvFJ4"
                + "OLbOPGtjdI7PnWsr9Q1UmSPC/U5824MZDk3FKsrQYbm+7mcoGM2pc76jfQYh1bE14tq3e0/13yu2ZdK2sDh0FEGr2P+SLFLkZqisIppjOnZH"
                + "LvedNJ8EEw5BQekOdCLC/aWVX9rV0+9DqSg0zIk5SvVZCTZyhSB3RX2ZJPzt6KRYUYyO4g+NbIElR+V9e+pc0tp3deRhp96lto0lV2e155YF"
                + "DC+BqiVSlPk//1kI7nVZBW6X3kwjs5MY/cBdIsVuuDMYZDqGhw63hYSncp/co0liHfJuIoEvqXtcRySkRGcE0dZIEGPDndHjscsr2UJUKJ7O"
                + "gHPNIBdNttGdvqp6RGsIRhjigQ5ofLYEJMOLPpyXOXByAeaPaMoUaNEfcgO5I+w/28pjdbru32BZxjocCdI01tyxCqIUSyVYAteAv4uOPtV2"
                + "QepThffE3Jo2O6ZQQdXkkgRZPZnx7G+zx9gxNe8U3MxwuJqS8XecPlsAEQEAAbQsU3VibGltZSBIUSBQdHkgTHRkIDxzdXBwb3J0QHN1Ymxp"
                + "bWV0ZXh0LmNvbT6JAjQEEwECAB4FAlkQsPACGy8DCwkHBBUKCQgFFgIDAQACHgECF4AACgkQykZKmiItI9C0LA//dyJWXr02G4PTZkz7FWcf"
                + "c5QSzvagCG+HKPnTpPRaIXOxTGEnr3p6QOz05Tvt8gESYaqge+fu7uS2rYxMmKP1RKb2YQ8oaKz1B7IaCHB57A6QvlGuc6uNnnzXZTxPVa95"
                + "JVUe4Cm19hcY6lC+UhOv2CyCK9j4aXW2zEGzgh+rOJF5JbFcrI0bu5NCKVOFxfdLOIp10yCzK7gPC3VufaunYORI+LVL+eHff/yZdkc9M/E2"
                + "4LmD8BRB2l0Tb4Tb9KBZ47derGRDWmq/NRPonMO5Wh8Rij52dV0VJb7GuNP4U2I+tsgRs1IS21jx7zrO1KyJL7MohPud3A3xH6BYf3usi85E"
                + "hL9dscIZOKeRo3i55IAu1ruFRwWQ4/WrTjLsLXMZP3jaXVyoNFQNfmaNagri2ne3Imkihh02rBkhFVI77ZwzD2gM73PKNJJouJc2Zeox3g6t"
                + "iCq4JE/xShBfR6xEe5hafpo5VaeuQDP8cGeyWtt6HJ7gFc+I/UubOIs+nZHSTWJM2Ymeu0zLYftUHNA+i6E8B7nzCUB1JlBNfn30SjqV4puA"
                + "hNtXjvlm17znD4dtCob+BOzuIeuvfouTs/cP6gytqVRGMIkv+y8WkqGGix4NcSVpNbEMeIK8hb5IWTJTftgd3CQ+FyEmuuU1oQ0jMlq8BJZ1"
                + "3IXxHPdLL45Bv5+ZAg0EaQl29gEQANaF3JIczyP8AOnhi5WycHpGQtUmhAj0DnS4yK4O42Tx/Tk2lknb1tK2fibj+wHSMzYvsvoMroid4e9T"
                + "7qhR+wzKV69npKuyNOJu3cB2ju+a0R9fPWrNnVBsTEmlgFfNQ0Ygh2x1QaC2RM7p0E/NlYD3OGw3wGQwkQl+cmDIldXt5eqIdRosh8IUrLd/"
                + "3sGsuyz9tctjZ3d3Un/Xi6gaGelNXW526vD+LgXtaumKBq5QDIqq9ZylIJjqJcUYCFfvBMAqeQLuj+dEjgfGlkDTVtS3jcEwwzRLvXHFbniQ"
                + "38lVFGxVcS2AT5mBgHlQb02NIiQXr3k8SqO2C/fm2mQqcNT3ibtaz7JznUOTJb/oqAY7YVJzGQNvKYXxUmXsk72tlRwDrTHjluZs9Tp7HUFP"
                + "gEiITXiZkys4fzROaaAkO5TUqWMwMvQv62zi2y/mFo9EMHI6EuFB65FEyduGFABB4/2iLdK8uXVvLvrO3DLbnC9+TALgeuY7gqh64N6g/nCu"
                + "UhWQm/gE73LSMnKlD5iNLFLeG8KG1mMho3KCPNX59F/06pV5lX9LQuDuOBwMvoHUgxJteni49MCR9yIONo2GAheIriOFJBkwuYm8qzRRB0g7"
                + "T1xMJRXupPHYg8/wD9WlcAkUunDc1AZjnS4tJjBHkOU6qfvbqs0XbEaB8s9w6q6dABEBAAG0LFN1YmxpbWUgSFEgUHR5IEx0ZCA8c3VwcG9y"
                + "dEBzdWJsaW1ldGV4dC5jb20+iQJRBBMBCgA7FiEE68czt4qrNS3Hc7+Ff+L6Es9uOPIFAmkJdvYCGwMFCwkIBwICIgIGFQoJCAsCBBYCAwEC"
                + "HgcCF4AACgkQf+L6Es9uOPLceRAAsy6KHNiupPN4ziPwUA1XbL5zP+bKoOwH5nxed+Pz9k/uYDSEE57zvJXqWdradppabanS4wWClFzypnUe"
                + "CFqUEh+SmtYGW6TKcWdSUZHOzjj1rKGJ4VArucc/PlH53/VkrXBV4d3tVapHy8HDsbUtz9kdkJgtWHYMDbdSAUEg6iwhQBpprZzZe/XLmWMQ"
                + "OJFbGrri+AidLNweMDJ1oiiOhPm0Rgtgs9tc9D+NLLCwNKzORUB0ZUlKFpAsxjf7DnPtyzOFCVwrci48JOMh0b6nsUYuKbOaaNy2SkZbWYe6"
                + "hjnx899wHt1MCjawKSGHT5W6aX26WUj/wdXFD98YNXVesFcTnqmx9/4XYtgoHEBBONMYYmUhDvssM4rMuztxvAXTjQs2dOekOxcVScgrynkg"
                + "+b5T4jyUC252KjlVk2P00eJjI99hcdGLY/znIO6egjk3GjFNxQW2OujOnYfbc4WNtjWRlUf+L3iRo2RuKTMVPjbHDhfIzGDZBu0efrfvqknd"
                + "tEw2wTf3tStnhz0K97nBbucAsK7Y3vh0WI8lJ4kr1cGJ4S9NLjewhYNoulCBmVzoyx3/exifKBzvUtHll2b+D3wSrnmjKqny05NLIfYefJ6u"
                + "TdCDaO4U17WpRdsd0NFluvcMo6PUVEBkOaIcJz8DBnkHY3BdkGDnfbOm8s0GWGk=",
        )

        /** Element's, 12D4 CD60 0C22 40A9 F4A8 2071 D7B0 B669 41D0 1538. */
        private val ELEMENT = VendorRepository(
            "/usr/share/keyrings/element-io-archive-keyring.gpg", "element-io.list",
            listOf("deb [signed-by=/usr/share/keyrings/element-io-archive-keyring.gpg] https://packages.element.io/debian/ default main"),
            "mQINBFy1FpcBEADemFRfa16qbsgvnEq5TPhFOssXfSLG4eGBrU0O6adDwv6QyE53fivsepaZ21xLXP8KdfJBe40XmsYDLk6I+1cQIoKLCDhN"
                + "/omaCivJ0QwsHKFqdhsD0mmGpRzN1nNXOV856tcWsj25T4V2ttPumvCV/ArITta0X2GPbF2oYKbKjE93uZWRxogqHrD7QVzjlDvU6+gQ/TzI"
                + "A/k0cG/LlOqhHTrR/VMvSzE9LDn2YoWaC2Hk2NZEUby788vombTgPhTrCUmQwDsaXYUfILLhaiAdCqNc3aMcNjc3VX1YjJG0pArx9V2NRPMR"
                + "2UZQzSLgthEz/om9k7x9A9RG85Jo2AAmjrpIl4NRawpKP+uXtIdr4huCzWT4r8e1DiMORKRvRPzua/kf+i8vjKWy16KRD5N6rNOTjfoSQxkQ"
                + "Tgh9fvLgJUAJ+UnLgLKXaijyyIisQ6O2zaI5jJMuSzBG129xpdCeNB0Vmfuy8fBGttTg+OoP1mhnQtDAmh7k5EefFKDoKKgt2m+C6nlLr7pG"
                + "9EA5qMHbQikmZo33phi/yIIU0w8RahueC7A1rCvDla+lr9Y2o0Y+2VGTqkc37WadiCcF6DZ/rKMoajgafbJV3QsVBdD0rraqLfvK/+UfbbJu"
                + "Zdxb7LtBMGL35ENrVfFNZDiEFJs0eumDCk/KLGBVlL25PH6kIwARAQABtCNyaW90LmltIHBhY2thZ2VzIDxwYWNrYWdlc0ByaW90LmltPokC"
                + "VAQTAQoAPgIbAwULCQgHAgYVCgkICwIEFgIDAQIeAQIXgBYhBBLUzWAMIkCp9KggcdewtmlB0BU4BQJkE2bTBQkaKlM8AAoJENewtmlB0BU4"
                + "3pEQAJdDZB9K6I96r47wHWVFN7zfuY7uvDHXQ2k4bjBIayDuDmShFaA9IhIkE9aeZz0tOjZCEs1zl0SeE0pWf69FPKOR0nxsGJnM8lacIkGH"
                + "WxnYzGD7dBP1k5z1efYirwZmHjf3FMqN1rjxkT9LLgPOl5DDu927hxMfP7K0BNRBXg7ARq7siKkUxqvZSwotD6M8mpfr48K1new/DMEEbuGB"
                + "p5O3gmh3pPqUiNw2Q8aEbNjajz8fME+Tt5mp0wS40B19uPlYzqH+hIbDq+rhlyEaSu4CuqJSY/IMhFqbs90d2NrrFjyvp/5xxXPv2AUu8Mur"
                + "svtmrq+LI0qoAszZyPqNhpQ1/mtXAfrq89WqUADgkUmcnBirlV0WAxWyBou9VbE6tse6jNHGwruGwFP61sSWmK1aNsbQcsIjNm4062LcvA8o"
                + "ufVqp7FrlsQmzeZge+rBrqJi9d8mRd3h4cykIyYiu8aTyVx7toosxXprPSZR9HuTLcl6fb3DwRCcsrmEeUNx2rW55BnHEXvltPAkd5N583Ur"
                + "QlyMQ2+OxKOtiNw/6v0oF5RRA2oi7UfjwJDb14uDV5xhhP/sh/SEhmGsf40U40VaTFH0m4FGwD/+V9l+eKkoVbc9IQziIgll/g1zFXfS2F6A"
                + "bNIrEu2yr2dKru++ZJ8mixmSpMR3GBbbHheEpgLwuQGNBFy1FtQBDADPalE7/hP0kt7afhFoY/sGyO/464BA4OzoMaQC28d4JJCd07upnyj1"
                + "aLGHfYyO6TXC1cqOQ2tThENyTfJOhVDQ9YCjqDzm4S5VR91tNzvYNZOEIwRRPND2jpnmsCzwrnIRHNIiojHBZRnPdC01zcx4oC1m13qDiFSU"
                + "NOi/uDlAXtOf8p0zVnPypaGTG7MUBU8RmkyygvG+Z6AqNDOsDL/nIC5mf2zmLJqKVkEeXnWhWBEVgIdr840vi/ejblmVRxanlyGVFY/5CWgy"
                + "lmGxxB0Oh5vz7SjpK5H5pONBo43K2tEjnU1jmWTX7tkHYo8wyQS04uO33qh01FLnYl1I0qebfwBys88i/yhr9afxcXae5xTLUPzPp+6WYICx"
                + "RdJ41/3zwlyKbNLvyNQzv43kiRYNR3Yc44F1tHMq1Ty3kca7Qe0zGXXeISY3fUA4zKjg0S8bi3yfO5Z/FxpMhjJ+tAcDoiVrXZwsXCsdMnQR"
                + "0KVjzIAmCuJI7OUnujuAB9aMYSEAEQEAAYkD8gQYAQoAJgIbAhYhBBLUzWAMIkCp9KggcdewtmlB0BU4BQJnxZR4BQkO4rakAcDA9CAEGQEK"
                + "AB0WIQR1dBiQBj5emkYTXQHChQsmWsCFvQUCXLUW1AAKCRDChQsmWsCFvaDYDADPVBNm75uZtEPOM2CtoxASarbPDLz8Ucy6FCtOoSpNdgAZ"
                + "FTISFASWfBO6h/9w5czT3owQD431V950QBHGt763VFILckZ0Ul4roGGesmncRUIZLrc+UABigirHmCdnvo9s5UszTxid0muMbDeLb1RmI0tk"
                + "RDzlk/TrkHDf7rIUrcqhPqhtR0b75MfosEaowVN+kS9PqyFtXsrKB/iM/gjvVnEEfIVDaK+lc6EBbqfJLMCa5z63CSEqMUhWP0qXGoA7ZM6A"
                + "zaplzCTr5aB9dQBNU53SUo35OzblQSqR0gyuCYrvOHtisjTdrrUNsIbyjkUOc5Umpxzs9XmY94D5FfdxeALvYcs2hMEQWPoINVx87p1tWjwn"
                + "mPzXGm2q095gL+ysOS5OeKOaPEPWfUe7NUd/WJ3GqvtPiF++PMEDBiPBm5gwrfg8Nd9xNoRntRZoOKJDcJ2/hhH5+4zPW54O8Z4xBaOGjbWY"
                + "TMxKw/M9sRmHIvXVcQmWdPhCOIP1XQndJoAJENewtmlB0BU4j/gP/joEHCWocUhlR7w/KiaE9PSedxW94iK82KNodwkJCmmn86PfFgUU9xFW"
                + "tfee4XZcf3UwXp0NDggCawkiwNuwuo339Dv34Td3hkLbwmxqcILDRuHxZAN7VHQczvAfA4OqrgWJSNJefUofMdnc+fU8y9hULzw1k6BoN23V"
                + "3AbbrrCNEM00OTA33+p1aDI6O/2ueMSf/58SvlLIhRYf6gZ9u37tRzzU4v4Kd7yuwkCQBS0jjaObXUaM9JNvt9+ztGbtPJq7RFeYHonTgefi"
                + "tMx4bz6DYv0e1PpeG6Ps3xl57SGWeX0f9O2KgcVTudbLzGao8XOSh9Kkv8A4dkLMjFhN96FcBV+ka1fY/bhi0H2P1+WP8sI06WBHNREveUy4"
                + "Oi0zGWE9TzwQ9+H1lDU1eB2OYUNQoOGiWJidFnMp+0qpJtZ6C580e0mct1j5nkQj8gRYtp90qOwNHJ7M/3t2erXzfRLVjwYoL/xiZOS5WMCT"
                + "mXjRYJ3Q1emb0LbnuOStpnz31bTY5H95uoYu/kFVYLChx+dERlZoBuzKTNBQ+WTg/7CJ7I/AV2X+FkdokykRpigBzSjjLfkabmkYxWRubdPF"
                + "Sq2VEK6E2yA4ODXVN2DQhJKE7wEQPGtRohxjKqPG5SpnPb2pCUMRXUbolw0nsNNm0eiWqq9/HdJbGpMoBiDLCFuO",
        )

        /** GitHub CLI's, 2C61 0620 1985 B60E 6C7A C873 23F3 D4EA 7571 6059 (and its next one). */
        private val GITHUB_CLI = VendorRepository(
            "/etc/apt/keyrings/githubcli-archive-keyring.gpg", "github-cli.list",
            listOf("deb [signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main"),
            "mQINBGMXLooBEADPmB8Gfd9kLqkIKnAnOktQqRwjjOWLTRV9fhGPlkuVQCffhu1b/x6pLHwC/c5ZRB2Y9SDXxbYAKHunA1AvxHlc6OFciUAM"
                + "pE0ygAGyuMmC3CwyGE3q/SjvOe982Wpjg0J2FTpGiQ2C/isCtkfEsFR3sofV/SzCBbuIYQE5TrGEAfF0+jEhxmZQh5N7Rh56WEqp6AYzc/fy"
                + "LUddQTvCcR9tHu1GHz4JohUVj9vEAvyy2Gv8mG8oY1Er3LedUfsS9Bgk2GaKN8lTG+UUSl+WRMCMje2FNaI1Xg46l0dmMZL3JUvrKrOvaBvH"
                + "VSS2gUI8tTh7lbLfbcAGs6Su+D3oqBbbG5eeyt8qIgZ+QBuk63+N+k2H/vrMHm6m5HS0K9+qxeFetrqRh0UKHhhDJL6BnRGhW1S22Dr/e0G7"
                + "MArgPRiYGKoenNh3FNmgbn+cOjfhkZ4yXUHXemn8lbIAkcXYnsND7jEZPWcOjoyVGLm2xPrTGq+OhkUQ9NmhLpzPszWeNzMDoP51+O/iTAJD"
                + "YQSciIKiYAgzgTZXsvrZBjRKibp6Nm3Q8hgZ4IH7dGhVULUyxFSu6+XM00pJu/KELMzC7ebaGyhPo5u8VDRgqIExtLEnr7LyVOI9BfK+Q6HX"
                + "SCIrp1HxBCwf/m9LUh66k7uBTL5ELyHiW+qpofpxOFxazAuFQQARAQABtCZHaXRIdWIgQ0xJIDxvcGVuc291cmNlK2NsaUBnaXRodWIuY29t"
                + "PokCVAQTAQgAPgIbAwULCQgHAgYVCgkICwIEFgIDAQIeAQIXgBYhBCxhBiAZhbYObHrIcyPz1Op1cWBZBQJm2aeEBQkHhN/6AAoJECPz1Op1"
                + "cWBZIAsP/31E31GA4yIyXYtbQkRGvX0FG0F5aLgot3BpZcV72V+KbujeqCH1Jful/fvQ0OClef+hoBIQ7FIg+73e4cfcWB6V6Nt5W7HcVDwp"
                + "rVzkCbvsfeV4riosaMOw0vy9cTBND6U4rYbnAJRgOQlLn725EGTFwaMhggAGcBkLZLp44Ioa4ynwt0UkYWxoeRM0pIX45BiLO3TWIhmYPZHa"
                + "zXgCge3kD0dlrDKhF2RTjUDckpKqpGJfJFIe1Fmuog4ZhMZrwUou/5Wlf/mHTf4WomFaqPIAMDA0HQih5KKcUCDbczW9E5EO7ijpJJU9Z+7q"
                + "PQRT5PjM7Q7uJPir4LrAtPT8F/acidEWZeTiQrp0Q2LIEqZHLJ0CERpHHn9HGbDVhFf6Ev/SyNwAk2ShmM/MSGSBZq+fY5Bck9hmtfQKz2TY"
                + "gtKF8I6wlF+/JF6YN2iqplx4/yWUaub2K474o6MSTPVfAdeEcCRKKeD0P5xDlL4/vz+fYW5dExGK0yJrbF/neC6mvTTRuhajL4I+kVYDt5Vj"
                + "2YTHEfzDT81BubWOGPq25Qa2qxFE/6URurDU7gxBoMfxnNoftoiubkvlcW+DKbkJbN1+dTk2d4zlcOJQ4alcfmFplxK0QIdMIy5iWRzC2leb"
                + "lEpj03hAQLVU3lrUT75eFdwuNq2xdSoHeiMdfOFKAEZeuQINBGMXLooBEAD44lZGLqXrzB0O1fcEho524DphTDewrArUp/WcwIkVOKLs47FX"
                + "JUMhFqI5aXizptuGnZ+yLXsc8022kS0MaYgAG+XERRru2Jivgr6fXwXq14ZJY2p9zuk8Gm7u8Gf9xXHPTAPvqm7ly13hoIQ8+h0kMZdk0vbA"
                + "k78cUKPDfl08aM31KLfuthnllLBGYxAk7hjFbn85MhO5VKMSCJnZ3bkvh9oSRDyWGWW78l7Tyee1IFooaLVQnx0c6e39hLR52RJPhEYO9WEM"
                + "q37+ZAdSW912Jv8QAUMzy72gYKj5GBqDOA/zq3e/K5mlncKmZh00+9LbC5i3FHrxkrzxCG6dQAWFWv1Z2cvVg+ara40Vwv6+TfitHIUdvFdq"
                + "l8gOQgQj0Ncv0cs2IGXok8EJyIufj19vPVPfStpnb0zCWL97gULdf0G7k6GL5LoGRPn7FsKB9qZYfphvrPePQaJ+5fK12SoPS3K2kWCMsOVr"
                + "IdE06AvD+qsA85+gXMHQ94hJPcGGBcDtbWspcWhjUf5VbVvNOTnNoq7G0yCbOLmkBt1RjWFZGCCwOcPXktzJ8p0j0ecE1E/lqOSoo02ydw9T"
                + "RlIzutDRaWi1HY5IV9zjKjmXh8L8bDtmP2pGIUOMCBpnOpvHKLVZogZsO7zAfFZHFkOVA4FW7kYr+SIo9inf3lFN6jASXwARAQABiQI8BBgB"
                + "CAAmAhsMFiEELGEGIBmFtg5seshzI/PU6nVxYFkFAmbZp5oFCQeE4BAACgkQI/PU6nVxYFkKVBAAnby3gX1tx103SfK1gUFmnQDhVXht/pB+"
                + "Ta0wu+VJgrZ73ZKAYnncLmq3fOWNoHud25b++fx3W2R/rV9hb39vbiZ6nTQDpdHpnvF+yYEN7a8BwcCLLT50tSgasILAmKNE59siET+hcONn"
                + "1qWtuMCtQF3UOzXxE3agPrdH+Wl2V23F1yJ+I6MLNC51EnhpjmrtcrH5OaZf5zdbey56qHqIzs6V68xyPj2amEFJDJQLakZphYu8RoP7162I"
                + "CgOf+BejSyDOJasWAH/yYYtd6X5O5wh6ynSgLKqc1TjkiJ+lAZE4DZ6fRVQTc6Q0hCWUfGUn57gNUYCvRaQ8m/X6EdJseooO1mrqLxUT8qxu"
                + "NDXUljG48bDRxDTnVMYBqIGxi2DVEw8OjaaZuHuYZLT8U2E7S+HCL7O7QLwIidWh8vi2I2rrZ3lDG/xsRPxnOkSB1kApE57BbaKHashKXmQr"
                + "Zk9UXrcvbT0LzP0EAqIsFSr7r9NxfCJ+bSM0RFxyIrOTeOuPFT77TZuTEUEkehYwxmJAbQ7pIm1Pr+oBFDEOTrWydVu0x3SAtGx4J3Bnbe8N"
                + "ktjawoKaaG1Ob4l3TBJ1H2GHOJ6w/P5lO96/dEtYPHPx1AJqMkLm5kz7gxmCyRx9nRD3BvFDD4OIy4kTsd327uwEld3EADUE8jV+eCAWuR9u"
                + "Il6ZAg0EadTh+AEQAMqiN9VlDsfgpjQksBPmu9GwHu2gCxpxTNSL994P0l0QZBvmDXz0U4HYoKDYGKfqE/AXQOkIxntcSFGC3VUrkeRIDX9/"
                + "6og03UkSlHKPl+7vJX3eKnNLkhIlX9NpR2D0kgVP1Cjr2wgUkH7v16kgJC4Z/SfW4Bcucscx354gzzk/VHYn/tVPCMoxAKeg2MuFCkp2xKKW"
                + "1wS3NfBnmOSdXn7yJEJu+2JhCbyy134iWzEF0vTax4oqy1ZDOIJvaaXvcgjmTielpc3lv/U3qub3Qzc/7xPrjkLcRCVuHLYdJze64yMy+5dA"
                + "h0jL22MY/Ibh4A7SjG6EdaFZ2aSFcKzw9LgjF8cfVEyVuYaBRekOTqoOwi5qk5ycP65Lq0tmE6RIZyF+JzdV4Y3QXXeRv/9MjMdX6/khxVMp"
                + "txKRdC7bPC0wUca/ZdyizF2nWp0I2Vtxo7jylNQoyWdP7/ZVQ8+RNrl/HcQ+VABeLIFZmD0bsCE9bHF2LwntbqD93QI5kJlJJTLvOQSHrxaK"
                + "/SbB/F5LStmum5wBGHAKhrxBgG05fQ4T/Io3HGsy+gRLX4WluYitYUknknARIPZwu+fEIRY+N5ackRK5s3UDQGwYlMpNlSE7U0+5W9cdvEp8"
                + "HNwYOb9jnN8aUw4t0qeFE0Zcl8xfdlTrjlGgsSYAbyVcBy9984hZABEBAAG0JkdpdEh1YiBDTEkgPG9wZW5zb3VyY2UrY2xpQGdpdGh1Yi5j"
                + "b20+iQJOBBMBCgA4FiEEfzi7tZ0GTbyz2E1yVhKzZGIxMyUFAmnU4fgCGwMFCwkIBwIGFQoJCAsCBBYCAwECHgECF4AACgkQVhKzZGIxMyXk"
                + "8g//dtDGfhRV3A7TYsbIYl7g0XBTIeUqrPxw5M1zco30he8vI38I7vJnFFyoPq37AuHE67JWkAvik8H7T9JYxcLcdMZ6sBS8z93vSqktQdWi"
                + "2QTNdumRZ5+/uMLbxtZA2z5SGBGxb/92ROd5jqLlLUNcqiba0zPiRKh/Sggu+3jzz/+AFF4nmiv1NK8/rFw79mnIDihstMFm4lGVoQm0lcc0"
                + "It9KXFS+JHHajpNsXHmUMymgJALQIL36+flHBFUe057biIAlI3lCayUrCHHSPnWmeTMQ+kRclEF5Ohgjwr8y6Qpx/c4/ktOMcEg08zdRMzP9"
                + "ZHRl0glfsQD1ussbwziDh6rlTacigNNbMRPgJb9z/iRnv1HT6kiTsjQ6gjiwB0fgukLbs8MwVFdFXOt4VrmAGn+Sbw2ZpzMFASkB53fr2bxN"
                + "ZAPqnI6if9HXEFdn4D/rIbIvfwVdWXk/DAlr2SPn8yWqAEfTfPRIPkbomaFsTMwi4m9VbvCzjWgl8Wkq32rkqnfloKFkzgIAF9i3eJV3TMz9"
                + "JgSagNqHyqoW1u+/75ZJcxUs8TiBP7sYtGWqtgnMUWGGald53agIkQcrX/BqQbnJsNyEoX8wOWES/dArb86VpGjs+V0pw01fDde16A6kiH/x"
                + "QMkQb0levXsYWaHWTTbmPSLeOMkT8u++QPU7Efm5Ag0EadTh+AEQAOfa/mEtEcLIrdRPqXGwZCiZxYmil1rauSlSIYu9w0yMnUCyqJMaGgtw"
                + "bQuRv7KKoYhpkbmx5LgyG6iwbHY69aYHLXqhxMtZKNIpHd0Lm01vCa4LMQ1g58MgnW/B5L5qJELjItNwMMFXo+jSxPcQA1/gxso+6qtHwU4I"
                + "FFCbD41V+ZzPp4dLbfp6SYNOPKaK9u1ZgYmiqgjV/Z1D0w22lQbTOnmMt9jINdspJg+Y2EhdpMTLsBpCozslx5fYIJQjDOSBWXdg+56cDTCE"
                + "w+GB16svzrv7Dmuq2I5wT6cUnlfoRNBPU/mKOQNS1GVHrQLgjtDswn5azPhivJl0FEJVkc0J7RDbLo04twc02N0lgQ8/4xTkw6PrC5fNoJC5"
                + "iBoxrJ/iwqG9DdXCywGMwemIphgPf4hhJ+Ip5NwbmOSFvwE1fq6cFKXq7ja/JVrpmsrKC3vD/163/s/bPba0owFztvcx5rs52bNdZwWN+2oe"
                + "+JZ8BTHAheZxh1TiIM+f9/reMsX4LhIPiE/FfYcxOD29WPyhVGazJEoU7Ka4li8EXaOcgzlPPl41tsOP36oU3wThiHai0kYvvddhgkYuFdre"
                + "QXnSImQMSXlXgovKHjU3a5HPd37ybbLBPh9SwKfEOnyjmB+w2t1n2fxMLGrPqUXtsZzDEeiCS8Ds8oNf/0DsMQV/ABEBAAGJAjYEGAEKACAW"
                + "IQR/OLu1nQZNvLPYTXJWErNkYjEzJQUCadTh+AIbDAAKCRBWErNkYjEzJYbBEAC52CTBmV1qGxycBX1ejaSt6aU6pkTetA9JnaFRg8hBAkQz"
                + "M0pxYUokhbO4bl7PblUm94dNQNpiKSD/ohS9cHAp6cUcN17GK5NwS9WFynHYuCnQ41/GtVHVfGptjX/bW6LtAI7GcHRBRqVlLBqzDDfqQIJt"
                + "4GOw2LsFGY0YejsKErMWxm45Y6o1z3B3Nv0Z6qob32YNNroe7frgjZCgQlyL14EfALTWar7lXQJquYMsgQKk9ugeavHlaLnkSd3YVMVHQi9v"
                + "vg7VTxyXpF+udDatnDyYNywXJrUloXulvn7LZDylPEZ3qzQwQxJxkgn8V48rf52ligmJa89egiHscUgykrm36ABmrMs6jMB0+cnj1iaLJU7h"
                + "tPHXKiv74UI3JjkSbIrNpV6vgNR+kC9NzVx3GZqpZQWxB95OYvSeXqpErKW+wnsNHizoR9QrECIHhXidOljmc2IAnQP61A6OHUCRjJ0FgCGm"
                + "FvdEB613PaRZ1wS+s0I7MwHFFT82aSlN4YDUqq12yR825KF99f7WnQ8mfWFniT3hLSdJCYm/GLXYB38kbW7Ip27Nrz5tqBV/1DHY6lfZ42+U"
                + "kNE93TOnB8CBsvVYjiWalaNBpVhr3pNrBP8G7Ib4v9BfECJQSB36v/wMTntpLSzEz8zmL9vCs9aBvUJlCrqVQ4bgLfi0e8MvFQ==",
        )

        /** The vendor repositories, by the package that comes from them. */
        val BY_PACKAGE = mapOf(
            "code" to MICROSOFT,
            "firefox" to MOZILLA,
            "brave-browser" to BRAVE,
            "sublime-text" to SUBLIME,
            "sublime-merge" to SUBLIME,
            "element-desktop" to ELEMENT,
            "gh" to GITHUB_CLI,
        )
    }
}
